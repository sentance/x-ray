package com.xray.ai;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.xray.analysis.AnalysisCache;
import com.xray.analysis.AnalysisService;
import com.xray.analysis.AnalysisService.Overview;
import com.xray.analysis.AnalysisService.StrategyReport;
import com.xray.analysis.BinanceLagAnalyzer.BinanceReport;
import com.xray.analysis.MarketResult;
import com.xray.analysis.PnlAnalyzer.PnlReport;
import com.xray.ingest.WalletRepository.Wallet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The wallet's computed reports as compact JSON, which is everything Claude is told about the wallet. */
@Component
public class WalletBriefing {

	/** Four significant digits are plenty for prose and cut the token count of long decimals. */
	private static final MathContext PRECISION = new MathContext(4);

	private final AnalysisService analysis;

	private final AnalysisCache cache;

	private final JsonMapper json;

	public WalletBriefing(AnalysisService analysis, AnalysisCache cache, JsonMapper json) {
		this.analysis = analysis;
		this.cache = cache;
		this.json = json;
	}

	public String build(String address) {
		Wallet wallet = analysis.wallet(address);
		Overview overview = analysis.overview(address);
		StrategyReport strategy = analysis.strategy(address);
		PnlReport pnl = analysis.pnl(address);

		Map<String, Object> brief = new LinkedHashMap<>();
		brief.put("wallet", Map.of("address", address, "name", Optional.ofNullable(wallet.name()).orElse(""),
				"pseudonym", Optional.ofNullable(wallet.pseudonym()).orElse(""), "activityCount",
				wallet.activityCount(), "lastSyncedAt", String.valueOf(wallet.lastSyncedAt())));
		brief.put("totals", overview.totals());
		brief.put("openPositionsValue", overview.openPositionsValue());
		brief.put("classification", strategy.classification());
		brief.put("profile", profile(strategy));
		brief.put("pnlByMonth", pnl.months());
		brief.put("ruleChangeMarkers", pnl.markers());
		brief.put("skillOrLuck", analysis.skill(address));
		brief.put("stillWorking", analysis.decay(address));
		cache.find(address, "binance", wallet.syncVersion(), BinanceReport.class)
			.ifPresent(b -> brief.put("binanceTest", b));
		return json.writeValueAsString(round(json.valueToTree(brief)));
	}

	/** The strategy profile without fields that only matter for links (condition ids, slugs). */
	private Map<String, Object> profile(StrategyReport strategy) {
		var p = strategy.profile();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("activity", p.activity());
		m.put("categories", p.categories());
		m.put("marketTypes", p.marketTypes());
		m.put("cryptoAssets", p.cryptoAssets());
		m.put("bothSides", p.bothSides());
		m.put("exits", p.exits());
		m.put("entryPrices", p.entryPrices());
		m.put("expiry", p.expiry());
		m.put("tradeSizes", p.tradeSizes());
		m.put("timingUtc", p.timing());
		m.put("topWinners", markets(p.topWinners()));
		m.put("topLosers", markets(p.topLosers()));
		m.put("estimatedMarketPnl", p.estimatedMarketPnl());
		return m;
	}

	private static List<Map<String, Object>> markets(List<MarketResult> markets) {
		return markets.stream().map(r -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("title", r.title());
			m.put("type", r.marketType());
			m.put("estimatedPnl", r.pnl());
			m.put("volume", r.volume());
			m.put("trades", r.trades());
			m.put("resolved", r.resolved());
			return m;
		}).toList();
	}

	private static JsonNode round(JsonNode node) {
		if (node instanceof ObjectNode o) {
			for (String name : List.copyOf(o.propertyNames())) {
				o.set(name, round(o.get(name)));
			}
			return o;
		}
		if (node instanceof ArrayNode a) {
			for (int i = 0; i < a.size(); i++) {
				a.set(i, round(a.get(i)));
			}
			return a;
		}
		if (node.isFloatingPointNumber()) {
			return node.decimalValue().compareTo(BigDecimal.ZERO) == 0 ? node
					: JsonMapper.shared().getNodeFactory()
						.numberNode(node.decimalValue().round(PRECISION).stripTrailingZeros());
		}
		return node;
	}
}
