package com.xray.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.xray.client.PolymarketClient;
import com.xray.client.PolymarketDtos.Activity;
import com.xray.client.PolymarketDtos.PnlPoint;
import com.xray.client.PolymarketDtos.Position;
import com.xray.config.XrayProperties;
import com.xray.ingest.ActivityRepository.KeyedActivity;
import com.xray.ingest.SyncJobRepository.Status;
import com.xray.ingest.SyncJobRepository.SyncJob;
import com.xray.market.MarketEnricher;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pulls a wallet's full public history into the database. The activity walk is resumable: it always continues from
 * the newest stored timestamp, so a first sync backfills everything and later syncs only fetch what is new.
 */
@Service
public class WalletSyncService {

	private static final Logger log = LoggerFactory.getLogger(WalletSyncService.class);

	private static final int POSITION_PAGE_SIZE = 500;

	private static final int MAX_POSITION_PAGES = 4;

	private final PolymarketClient polymarket;

	private final WalletRepository wallets;

	private final SyncJobRepository jobs;

	private final ActivityRepository activities;

	private final PnlRepository pnl;

	private final PositionRepository positions;

	private final MarketEnricher marketEnricher;

	private final JsonMapper json;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final Semaphore slots;

	private final Map<String, Long> running = new ConcurrentHashMap<>();

	public WalletSyncService(PolymarketClient polymarket, WalletRepository wallets, SyncJobRepository jobs,
			ActivityRepository activities, PnlRepository pnl, PositionRepository positions,
			MarketEnricher marketEnricher, JsonMapper json, XrayProperties properties) {
		this.polymarket = polymarket;
		this.wallets = wallets;
		this.jobs = jobs;
		this.activities = activities;
		this.pnl = pnl;
		this.positions = positions;
		this.marketEnricher = marketEnricher;
		this.json = json;
		this.slots = new Semaphore(properties.sync().maxConcurrentWallets());
	}

	@EventListener(ApplicationReadyEvent.class)
	void failInterruptedJobs() {
		int failed = jobs.failInterrupted();
		if (failed > 0) {
			log.info("Marked {} interrupted sync jobs as failed", failed);
		}
	}

	/** Starts a sync unless one is already running for this wallet, and returns the job to poll. */
	public synchronized SyncJob start(String address) {
		Long runningId = running.get(address);
		if (runningId != null) {
			return jobs.find(runningId).orElseThrow();
		}
		wallets.ensureExists(address);
		SyncJob job = jobs.create(address);
		running.put(address, job.id());
		executor.submit(() -> run(job));
		return job;
	}

	private void run(SyncJob job) {
		String address = job.address();
		try {
			slots.acquire();
			try {
				sync(job.id(), address);
				wallets.markSynced(address);
				jobs.finish(job.id(), Status.DONE, null);
				log.info("Synced {}", address);
			}
			finally {
				slots.release();
			}
		}
		catch (Exception e) {
			log.error("Sync of {} failed", address, e);
			jobs.finish(job.id(), Status.FAILED, e.getMessage());
		}
		finally {
			running.remove(address);
		}
	}

	private void sync(long jobId, String address) {
		jobs.phase(jobId, "PROFILE");
		polymarket.profile(address)
			.ifPresent(p -> wallets.updateProfile(address, p.name(), p.pseudonym(), json.writeValueAsString(p)));

		jobs.phase(jobId, "PNL");
		List<PnlPoint> points = polymarket.dailyPnl(address);
		pnl.replace(address, points);
		Long expected = points.isEmpty() ? null : points.getLast().tradeCount();

		jobs.phase(jobId, "ACTIVITY");
		syncActivity(jobId, address, expected);

		jobs.phase(jobId, "POSITIONS");
		syncPositions(address);

		jobs.phase(jobId, "MARKETS");
		marketEnricher.enrich(address);
	}

	/**
	 * Walks activity forward in time. The API caps the offset at {@link PolymarketClient#MAX_ACTIVITY_OFFSET}, so
	 * once a window is exhausted the walk restarts from the newest timestamp seen with a fresh offset; rows fetched
	 * twice at that boundary are dropped by their dedupe key.
	 */
	void syncActivity(long jobId, String address, Long expected) {
		Long newest = wallets.find(address).map(WalletRepository.Wallet::newestTs).orElse(null);
		long start = newest == null ? 0 : newest;
		long fetched = 0;
		while (true) {
			DedupeWindow window = new DedupeWindow();
			long windowMaxTs = start;
			boolean exhausted = false;
			for (int offset = 0; offset <= PolymarketClient.MAX_ACTIVITY_OFFSET; offset += PolymarketClient.ACTIVITY_PAGE_SIZE) {
				List<Activity> page = polymarket.activityAscending(address, start, offset);
				if (page == null || page.isEmpty()) {
					exhausted = true;
					break;
				}
				List<KeyedActivity> rows = new ArrayList<>(page.size());
				for (Activity a : page) {
					rows.add(new KeyedActivity(a, window.key(a)));
					windowMaxTs = Math.max(windowMaxTs, a.timestamp());
				}
				fetched += activities.insert(address, rows);
				wallets.updateCheckpoint(address, windowMaxTs);
				jobs.progress(jobId, fetched, expected);
				if (page.size() < PolymarketClient.ACTIVITY_PAGE_SIZE) {
					exhausted = true;
					break;
				}
			}
			if (exhausted) {
				return;
			}
			if (windowMaxTs == start) {
				// More than a full window of rows share one timestamp; skip past it rather than loop forever.
				log.warn("Activity for {} has over {} rows at ts {}; skipping ahead", address,
						PolymarketClient.MAX_ACTIVITY_OFFSET, start);
				windowMaxTs = start + 1;
			}
			start = windowMaxTs;
		}
	}

	private void syncPositions(String address) {
		List<Position> open = new ArrayList<>();
		for (int page = 0; page < MAX_POSITION_PAGES; page++) {
			List<Position> batch = polymarket.openPositions(address, POSITION_PAGE_SIZE, page * POSITION_PAGE_SIZE);
			if (batch == null || batch.isEmpty()) {
				break;
			}
			batch.stream().filter(p -> p.currentValue() != null && p.currentValue() > 0).forEach(open::add);
			if (batch.size() < POSITION_PAGE_SIZE) {
				break;
			}
		}
		double openValue = open.stream().mapToDouble(Position::currentValue).sum();
		positions.save(address, openValue, json.writeValueAsString(open));
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}
}
