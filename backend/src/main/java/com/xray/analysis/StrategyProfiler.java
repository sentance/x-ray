package com.xray.analysis;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.xray.analysis.ActivityStatsRepository.Bucket;
import com.xray.analysis.ActivityStatsRepository.MarketFlows;
import com.xray.analysis.ActivityStatsRepository.TypeTotal;
import com.xray.analysis.StrategyProfile.Activity;
import com.xray.analysis.StrategyProfile.BothSides;
import com.xray.analysis.StrategyProfile.EntryPrices;
import com.xray.analysis.StrategyProfile.Exits;
import com.xray.analysis.StrategyProfile.Expiry;
import com.xray.analysis.StrategyProfile.Share;
import com.xray.analysis.StrategyProfile.Timing;

@Component
public class StrategyProfiler {

	private static final Set<String> INCOME_TYPES = Set.of("REWARD", "MAKER_REBATE", "TAKER_REBATE", "YIELD",
			"REFERRAL_REWARD");

	private static final int TOP_MARKETS = 10;

	/**
	 * @param stats the wallet's activity aggregates, over its full history or a recent sample
	 * @param flows the wallet's per-market flows, over the same activity
	 * @param markets the same markets as {@link MarketResult}s
	 */
	public StrategyProfile profile(ActivityStats stats, List<MarketFlows> flows, List<MarketResult> markets) {
		List<TypeTotal> totals = stats.typeTotals();
		var timing = stats.timing();

		long buys = count(totals, "TRADE", "BUY");
		long sells = count(totals, "TRADE", "SELL");
		double buyUsdc = usdc(totals, "TRADE", "BUY");
		double sellUsdc = usdc(totals, "TRADE", "SELL");
		double income = totals.stream().filter(t -> INCOME_TYPES.contains(t.type())).mapToDouble(TypeTotal::usdc).sum();
		var activity = new Activity(buys + sells, buys, sells, buyUsdc, sellUsdc, markets.size(), timing.activeDays(),
				Stats.safeDiv(buys + sells, timing.activeDays()), income);

		double volume = markets.stream().mapToDouble(MarketResult::volume).sum();
		List<Share> categories = shares(markets, m -> m.category() == null ? "Other" : m.category(), volume);
		List<Share> marketTypes = shares(markets, MarketResult::marketType, volume);
		List<MarketResult> crypto = markets.stream().filter(m -> MarketTypes.binanceSymbol(m.slug()).isPresent()).toList();
		List<Share> assets = shares(crypto, m -> MarketTypes.binanceSymbol(m.slug()).orElseThrow().replace("USDT", ""),
				crypto.stream().mapToDouble(MarketResult::volume).sum());

		return new StrategyProfile(activity, categories, marketTypes, assets, bothSides(markets, volume),
				exits(flows, buyUsdc, sellUsdc), entryPrices(stats, flows), expiry(stats),
				stats.tradeSizes(), timing(timing), top(markets, Comparator.comparingDouble(MarketResult::pnl).reversed()),
				top(markets, Comparator.comparingDouble(MarketResult::pnl)),
				markets.stream().mapToDouble(MarketResult::pnl).sum());
	}

	private static List<Share> shares(List<MarketResult> markets, Function<MarketResult, String> key, double total) {
		Map<String, List<MarketResult>> groups = markets.stream().collect(Collectors.groupingBy(key));
		return groups.entrySet()
			.stream()
			.map(e -> {
				double v = e.getValue().stream().mapToDouble(MarketResult::volume).sum();
				return new Share(e.getKey(), e.getValue().stream().mapToLong(MarketResult::trades).sum(), v,
						Stats.safeDiv(v, total), e.getValue().stream().mapToDouble(MarketResult::pnl).sum());
			})
			.sorted(Comparator.comparingDouble(Share::volume).reversed())
			.toList();
	}

	private static BothSides bothSides(List<MarketResult> markets, double volume) {
		List<MarketResult> both = markets.stream().filter(MarketResult::bothSides).toList();
		double bothVolume = both.stream().mapToDouble(MarketResult::volume).sum();
		Double avgPairCost = both.isEmpty() ? null
				: both.stream().mapToDouble(m -> m.pairCost() * m.volume()).sum() / Math.max(bothVolume, 1e-9);
		long belowOne = both.stream().filter(m -> m.pairCost() < 1).count();
		return new BothSides(both.size(), Stats.safeDiv(both.size(), markets.size()), Stats.safeDiv(bothVolume, volume),
				avgPairCost, Stats.safeDiv(belowOne, both.size()));
	}

	private static Exits exits(List<MarketFlows> flows, double buyUsdc, double sellUsdc) {
		double heldUsdc = flows.stream()
			.filter(f -> f.boughtShares() > 0 && f.soldShares() / f.boughtShares() < 0.1)
			.mapToDouble(MarketFlows::buyUsdc)
			.sum();
		double mergeUsdc = flows.stream().mapToDouble(MarketFlows::mergeUsdc).sum();
		double redeemUsdc = flows.stream().mapToDouble(MarketFlows::redeemUsdc).sum();
		return new Exits(Stats.safeDiv(sellUsdc, buyUsdc), Stats.safeDiv(heldUsdc, buyUsdc),
				Stats.safeDiv(mergeUsdc, buyUsdc), redeemUsdc);
	}

	private static EntryPrices entryPrices(ActivityStats stats, List<MarketFlows> flows) {
		List<Bucket> buckets = stats.entryPriceBuckets();
		double total = buckets.stream().mapToDouble(Bucket::usdc).sum();
		double favourites = buckets.stream()
			.filter(b -> b.label().startsWith("0.90") || b.label().startsWith("≥"))
			.mapToDouble(Bucket::usdc)
			.sum();
		double longshots = buckets.stream().filter(b -> b.label().startsWith("<")).mapToDouble(Bucket::usdc).sum();
		double shares = flows.stream().mapToDouble(MarketFlows::boughtShares).sum();
		double usdc = flows.stream().mapToDouble(MarketFlows::buyUsdc).sum();
		return new EntryPrices(buckets, Stats.safeDiv(favourites, total), Stats.safeDiv(longshots, total),
				Stats.safeDiv(usdc, shares));
	}

	private static Expiry expiry(ActivityStats stats) {
		Double seconds = stats.medianSecondsToExpiry();
		return new Expiry(stats.timeToExpiryBuckets(), seconds == null ? null : seconds / 60);
	}

	private static Timing timing(ActivityStatsRepository.Timing t) {
		long total = 0;
		for (long c : t.hourOfDay()) {
			total += c;
		}
		// An hour counts as active if it sees at least a quarter of an even 24-hour share of trades.
		double threshold = total / 24.0 * 0.25;
		int activeHours = 0;
		for (long c : t.hourOfDay()) {
			if (c > 0 && c >= threshold) {
				activeHours++;
			}
		}
		return new Timing(t.hourOfDay(), t.dayOfWeek(), activeHours, t.medianGapSeconds());
	}

	private static List<MarketResult> top(List<MarketResult> markets, Comparator<MarketResult> order) {
		return markets.stream().sorted(order).limit(TOP_MARKETS).toList();
	}

	private static long count(List<TypeTotal> totals, String type, String side) {
		return totals.stream().filter(t -> t.type().equals(type) && t.side().equals(side)).mapToLong(TypeTotal::count).sum();
	}

	private static double usdc(List<TypeTotal> totals, String type, String side) {
		return totals.stream().filter(t -> t.type().equals(type) && t.side().equals(side)).mapToDouble(TypeTotal::usdc).sum();
	}
}
