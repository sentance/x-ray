package com.xray.binance;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.xray.analysis.ActivityStatsRepository;
import com.xray.analysis.ActivityStatsRepository.TradeRow;
import com.xray.analysis.AnalysisCache;
import com.xray.analysis.AnalysisService;
import com.xray.analysis.BinanceLagAnalyzer;
import com.xray.analysis.BinanceLagAnalyzer.BinanceReport;
import com.xray.analysis.BinanceLagAnalyzer.CryptoBuy;
import com.xray.analysis.MarketTypes;
import com.xray.ingest.WalletRepository.Wallet;

/**
 * Runs the Binance test for a wallet in the background: fetches the 1-minute candles its crypto buys need (shared
 * across wallets), then compares each buy with the surrounding spot moves. Results are cached per sync version.
 */
@Service
public class BinanceLagService {

	private static final Logger log = LoggerFactory.getLogger(BinanceLagService.class);

	public enum State {
		NONE, RUNNING, DONE, FAILED
	}

	/** {@code chunksDone}/{@code chunksNeeded} count candle requests to Binance while RUNNING. */
	public record Status(State state, int chunksDone, int chunksNeeded, BinanceReport report, String error) {
	}

	/** Candles per request, and so per stored chunk. */
	static final int CHUNK_MINUTES = BinanceClient.MAX_KLINES;

	/** The analyzer looks this far around each buy: 15 minutes before, the trade minute, then 16 after. */
	private static final int REACH_BEFORE = 15;

	private static final int REACH_AFTER = 16;

	/** A partly stored chunk this recent may still be filling in, so it is fetched again. */
	private static final Duration STILL_FILLING = Duration.ofHours(2);

	private static final String CACHE_KIND = "binance";

	private static final long MINUTE_MS = 60_000;

	private static final class Job {

		final AtomicInteger done = new AtomicInteger();

		volatile int needed;

		volatile String error;

		/** Kept here too, since a wallet still syncing doesn't get its result cached. */
		volatile BinanceReport report;

		/** The wallet data the report was computed from; a later sync makes it stale. */
		final int syncVersion;

		Job(int syncVersion) {
			this.syncVersion = syncVersion;
		}

		volatile boolean finished;
	}

	private final AnalysisService analysis;

	private final AnalysisCache cache;

	private final ActivityStatsRepository activityStats;

	private final KlineRepository klines;

	private final BinanceClient binance;

	private final BinanceLagAnalyzer analyzer;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final Map<String, Job> jobs = new ConcurrentHashMap<>();

	public BinanceLagService(AnalysisService analysis, AnalysisCache cache, ActivityStatsRepository activityStats,
			KlineRepository klines, BinanceClient binance, BinanceLagAnalyzer analyzer) {
		this.analysis = analysis;
		this.cache = cache;
		this.activityStats = activityStats;
		this.klines = klines;
		this.binance = binance;
		this.analyzer = analyzer;
	}

	public Status status(String address) {
		Wallet wallet = analysis.wallet(address);
		Job job = jobs.get(address);
		if (job != null && !job.finished) {
			return new Status(State.RUNNING, job.done.get(), job.needed, null, null);
		}
		Optional<BinanceReport> cached = cache.find(address, CACHE_KIND, wallet.syncVersion(), BinanceReport.class);
		if (cached.isPresent()) {
			return new Status(State.DONE, 0, 0, cached.get(), null);
		}
		if (job != null && job.report != null && job.syncVersion == wallet.syncVersion()) {
			return new Status(State.DONE, 0, 0, job.report, null);
		}
		if (job != null && job.error != null) {
			return new Status(State.FAILED, 0, 0, null, job.error);
		}
		return new Status(State.NONE, 0, 0, null, null);
	}

	/** Starts the test unless it is running or already has a result for the wallet's current data. */
	public synchronized Status start(String address) {
		Status current = status(address);
		if (current.state() == State.RUNNING || current.state() == State.DONE) {
			return current;
		}
		Job job = new Job(analysis.wallet(address).syncVersion());
		jobs.put(address, job);
		executor.submit(() -> run(address, job));
		return status(address);
	}

	private void run(String address, Job job) {
		try {
			boolean syncing = analysis.syncing(address);
			List<CryptoBuy> buys = cryptoBuys(address);
			Map<String, Set<Long>> chunks = neededChunks(buys);
			Map<String, List<Long>> missing = new HashMap<>();
			chunks.forEach((symbol, wanted) -> missing.put(symbol, missingChunks(symbol, wanted)));
			job.needed = missing.values().stream().mapToInt(List::size).sum();
			for (var e : missing.entrySet()) {
				for (long chunk : e.getValue()) {
					klines.save(e.getKey(), binance.minuteKlines(e.getKey(), chunk * CHUNK_MINUTES * MINUTE_MS));
					job.done.incrementAndGet();
				}
			}

			Map<String, Map<Long, Double>> opens = new HashMap<>();
			chunks.forEach((symbol, wanted) -> {
				TreeSet<Long> sorted = new TreeSet<>(wanted);
				opens.put(symbol, klines.opens(symbol, sorted.first() * CHUNK_MINUTES * MINUTE_MS,
						(sorted.last() + 1) * CHUNK_MINUTES * MINUTE_MS - 1));
			});
			BinanceReport report = analyzer.analyze(buys, (symbol, minuteMs) -> {
				Map<Long, Double> bySymbol = opens.get(symbol);
				return bySymbol == null ? null : bySymbol.get(minuteMs);
			});
			// A wallet still syncing will have more trades soon, so its result is not cached.
			if (!syncing) {
				cache.put(address, CACHE_KIND, job.syncVersion, report);
			}
			job.report = report;
		}
		catch (Exception e) {
			log.error("Binance test of {} failed", address, e);
			job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		}
		finally {
			job.finished = true;
		}
	}

	private List<CryptoBuy> cryptoBuys(String address) {
		List<CryptoBuy> buys = new ArrayList<>();
		for (TradeRow t : activityStats.cryptoBuys(address)) {
			Optional<String> symbol = MarketTypes.binanceSymbol(t.slug());
			if (symbol.isPresent()) {
				buys.add(new CryptoBuy(t.ts(), symbol.get(), MarketTypes.direction(t.slug(), t.outcome()), t.usdc(),
						MarketTypes.windowStart(t.slug()).orElse(null)));
			}
		}
		return buys;
	}

	/** Chunks covering every minute the analyzer will look up, per symbol. */
	private static Map<String, Set<Long>> neededChunks(List<CryptoBuy> buys) {
		Map<String, Set<Long>> chunks = new HashMap<>();
		for (CryptoBuy b : buys) {
			if (b.direction() == 0) {
				continue;
			}
			Set<Long> set = chunks.computeIfAbsent(b.symbol(), s -> new HashSet<>());
			long minute = Math.floorDiv(b.ts(), 60);
			// A window spans fewer minutes than a chunk, so its two ends cover it.
			set.add(Math.floorDiv(minute - REACH_BEFORE, CHUNK_MINUTES));
			set.add(Math.floorDiv(minute + REACH_AFTER, CHUNK_MINUTES));
			if (b.windowStartTs() != null) {
				set.add(Math.floorDiv(Math.floorDiv(b.windowStartTs(), 60), CHUNK_MINUTES));
			}
		}
		return chunks;
	}

	private List<Long> missingChunks(String symbol, Set<Long> wanted) {
		Map<Long, Long> stored = klines.chunkCounts(symbol, wanted, CHUNK_MINUTES);
		long fillingFromMinute = Instant.now().minus(STILL_FILLING).getEpochSecond() / 60;
		return wanted.stream().sorted().filter(chunk -> {
			long count = stored.getOrDefault(chunk, 0L);
			boolean recent = (chunk + 1) * CHUNK_MINUTES > fillingFromMinute;
			return count == 0 || (count < CHUNK_MINUTES && recent);
		}).toList();
	}
}
