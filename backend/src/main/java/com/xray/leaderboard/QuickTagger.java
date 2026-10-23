package com.xray.leaderboard;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.xray.analysis.ActivityStatsRepository.MarketFlows;
import com.xray.analysis.ActivityStatsRepository.TypeTotal;
import com.xray.analysis.MarketResult;
import com.xray.analysis.PnlAnalyzer.Totals;
import com.xray.analysis.SampleStats;
import com.xray.analysis.StrategyClassifier;
import com.xray.analysis.StrategyClassifier.Classification;
import com.xray.analysis.StrategyProfile;
import com.xray.analysis.StrategyProfiler;
import com.xray.client.PolymarketClient;
import com.xray.client.PolymarketDtos.Activity;
import com.xray.leaderboard.TagRepository.QuickTag;
import com.xray.market.MarketRepository;

/**
 * Classifies leaderboard wallets from their most recent activity, one wallet at a time in the background, so the
 * leaderboard can show strategies without a full sync of every wallet on it.
 */
@Component
public class QuickTagger {

	private static final Logger log = LoggerFactory.getLogger(QuickTagger.class);

	/** Two pages of the activity endpoint: enough to see the pattern, cheap enough for 50 wallets. */
	static final int SAMPLE_SIZE = 1000;

	private static final int PAGE = PolymarketClient.ACTIVITY_PAGE_SIZE;

	/** A wallet whose tagging failed is not retried for this long, so polling clients don't hammer it. */
	private static final Duration RETRY_AFTER = Duration.ofMinutes(10);

	private final PolymarketClient polymarket;

	private final MarketRepository markets;

	private final StrategyProfiler profiler;

	private final StrategyClassifier classifier;

	private final TagRepository tags;

	// One worker: tagging shares the Polymarket rate limit with wallet syncs and should not crowd them out.
	private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

	private final Set<String> queued = ConcurrentHashMap.newKeySet();

	private final Map<String, Instant> failedAt = new ConcurrentHashMap<>();

	public QuickTagger(PolymarketClient polymarket, MarketRepository markets, StrategyProfiler profiler,
			StrategyClassifier classifier, TagRepository tags) {
		this.polymarket = polymarket;
		this.markets = markets;
		this.profiler = profiler;
		this.classifier = classifier;
		this.tags = tags;
	}

	/**
	 * Queues the wallet unless it is already queued or recently failed.
	 * @return whether a tag for it is on its way
	 */
	public boolean enqueue(String address) {
		Instant failed = failedAt.get(address);
		if (failed != null && failed.plus(RETRY_AFTER).isAfter(Instant.now())) {
			return false;
		}
		if (queued.add(address)) {
			worker.submit(() -> {
				try {
					tags.saveQuickTag(address, tag(address));
					failedAt.remove(address);
				}
				catch (Exception e) {
					log.warn("Quick tag of {} failed: {}", address, e.getMessage());
					failedAt.put(address, Instant.now());
				}
				finally {
					queued.remove(address);
				}
			});
		}
		return true;
	}

	QuickTag tag(String address) {
		List<Activity> sample = new ArrayList<>();
		for (int offset = 0; offset < SAMPLE_SIZE; offset += PAGE) {
			List<Activity> page = polymarket.recentActivity(address, PAGE, offset);
			if (page == null || page.isEmpty()) {
				break;
			}
			sample.addAll(page);
			if (page.size() < PAGE) {
				break;
			}
		}
		Set<String> conditionIds = sample.stream()
			.map(Activity::conditionId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		SampleStats stats = new SampleStats(sample, markets.find(conditionIds));
		if (stats.tradeCount() == 0) {
			return new QuickTag("No trades in its recent activity.", List.of(), 0, null, null);
		}
		List<MarketFlows> flows = stats.marketFlows();
		List<MarketResult> results = flows.stream().map(MarketResult::of).toList();
		StrategyProfile profile = profiler.profile(stats, flows, results);
		Classification c = classifier.classify(profile, totals(stats, results));
		return new QuickTag(c.summary(), c.labels(), stats.tradeCount(), stats.firstTradeTs(), stats.lastTradeTs());
	}

	/** The classifier only reads maker rebates and P&L from the totals; both come from the sample here. */
	private static Totals totals(SampleStats stats, List<MarketResult> results) {
		double makerRebate = stats.typeTotals()
			.stream()
			.filter(t -> t.type().equals("MAKER_REBATE"))
			.mapToDouble(TypeTotal::usdc)
			.sum();
		double pnl = results.stream().mapToDouble(MarketResult::pnl).sum();
		double volume = results.stream().mapToDouble(MarketResult::volume).sum();
		return new Totals(pnl, pnl, 0, volume, 0, makerRebate, 0, 0, stats.tradeCount(), volume > 0 ? pnl / volume : 0,
				stats.firstTradeTs(), stats.lastTradeTs());
	}
}
