package com.xray.analysis;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.xray.analysis.ActivityStatsRepository.MarketFlows;
import com.xray.analysis.DecayAnalyzer.DecayReport;
import com.xray.analysis.PnlAnalyzer.PnlReport;
import com.xray.analysis.SkillAnalyzer.SkillReport;
import com.xray.analysis.StrategyClassifier.Classification;
import com.xray.analysis.StrategyClassifier.Label;
import com.xray.ingest.PnlRepository;
import com.xray.ingest.PositionRepository;
import com.xray.ingest.SyncJobRepository;
import com.xray.ingest.SyncJobRepository.Status;
import com.xray.ingest.WalletRepository;
import com.xray.ingest.WalletRepository.Wallet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entry point for all wallet reports. Reports are cached per sync version; while a sync is still running they are
 * computed fresh from the partial data instead.
 */
@Service
public class AnalysisService {

	public record StrategyReport(StrategyProfile profile, Classification classification) {
	}

	public record Overview(Wallet wallet, JsonNode profile, PnlAnalyzer.Totals totals, double openPositionsValue,
			String summary, List<Label> labels, String skillVerdict, String decayVerdict) {
	}

	private final WalletRepository wallets;

	private final SyncJobRepository jobs;

	private final PnlRepository pnl;

	private final PositionRepository positions;

	private final ActivityStatsRepository activityStats;

	private final PnlAnalyzer pnlAnalyzer;

	private final SkillAnalyzer skillAnalyzer;

	private final DecayAnalyzer decayAnalyzer;

	private final StrategyProfiler profiler;

	private final StrategyClassifier classifier;

	private final AnalysisCache cache;

	private final JsonMapper json;

	public AnalysisService(WalletRepository wallets, SyncJobRepository jobs, PnlRepository pnl,
			PositionRepository positions, ActivityStatsRepository activityStats, PnlAnalyzer pnlAnalyzer,
			SkillAnalyzer skillAnalyzer, DecayAnalyzer decayAnalyzer, StrategyProfiler profiler,
			StrategyClassifier classifier, AnalysisCache cache, JsonMapper json) {
		this.wallets = wallets;
		this.jobs = jobs;
		this.pnl = pnl;
		this.positions = positions;
		this.activityStats = activityStats;
		this.pnlAnalyzer = pnlAnalyzer;
		this.skillAnalyzer = skillAnalyzer;
		this.decayAnalyzer = decayAnalyzer;
		this.profiler = profiler;
		this.classifier = classifier;
		this.cache = cache;
		this.json = json;
	}

	public PnlReport pnl(String address) {
		return cached(address, "pnl", PnlReport.class, () -> pnlAnalyzer.analyze(pnl.series(address)));
	}

	public SkillReport skill(String address) {
		return cached(address, "skill", SkillReport.class, () -> skillAnalyzer.analyze(
				DailyChange.of(pnl.series(address)),
				activityStats.marketFlows(address).stream().map(MarketResult::of).map(MarketResult::pnl).toList()));
	}

	public DecayReport decay(String address) {
		return cached(address, "decay", DecayReport.class, () -> decayAnalyzer.analyze(DailyChange.of(pnl.series(address))));
	}

	public StrategyReport strategy(String address) {
		return cached(address, "strategy", StrategyReport.class, () -> {
			List<MarketFlows> flows = activityStats.marketFlows(address);
			List<MarketResult> markets = flows.stream().map(MarketResult::of).toList();
			StrategyProfile profile = profiler.profile(address, flows, markets);
			return new StrategyReport(profile, classifier.classify(profile, pnl(address).totals()));
		});
	}

	public Overview overview(String address) {
		Wallet wallet = wallet(address);
		StrategyReport strategy = strategy(address);
		JsonNode profile = wallets.profileJson(address).map(json::readTree).orElse(null);
		return new Overview(wallet, profile, pnl(address).totals(),
				positions.find(address).map(PositionRepository.Snapshot::openValue).orElse(0.0),
				strategy.classification().summary(), strategy.classification().labels(), skill(address).verdict(),
				decay(address).verdict());
	}

	public Wallet wallet(String address) {
		return wallets.find(address)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not analyzed yet"));
	}

	/** Whether the stored data is final; reports on a wallet that is still syncing are not cached. */
	public boolean syncing(String address) {
		return jobs.latest(address).map(j -> j.status() == Status.RUNNING).orElse(false);
	}

	public <T> T cached(String address, String kind, Class<T> type, Supplier<T> compute) {
		Wallet wallet = wallet(address);
		if (syncing(address)) {
			return compute.get();
		}
		return cache.get(address, kind, wallet.syncVersion(), type, compute);
	}
}
