package com.xray.analysis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.xray.analysis.ActivityStatsRepository.Bucket;
import com.xray.analysis.ActivityStatsRepository.MarketFlows;
import com.xray.analysis.ActivityStatsRepository.SizePercentiles;
import com.xray.analysis.ActivityStatsRepository.Timing;
import com.xray.analysis.ActivityStatsRepository.TypeTotal;
import com.xray.client.PolymarketDtos.Activity;
import com.xray.market.MarketCategory;
import com.xray.market.MarketRepository.KnownMarket;

/**
 * {@link ActivityStats} and per-market flows computed in memory over a sample of a wallet's recent activity, for
 * tagging wallets that were never synced. Mirrors the SQL in {@link ActivityStatsRepository}.
 */
public final class SampleStats implements ActivityStats {

	private static final double[] PRICE_EDGES = { 0.10, 0.30, 0.50, 0.70, 0.90, 0.97 };

	private static final String[] PRICE_LABELS = { "<0.10", "0.10–0.30", "0.30–0.50", "0.50–0.70", "0.70–0.90",
			"0.90–0.97", "≥0.97" };

	private static final long[] EXPIRY_EDGES = { 0, 300, 900, 3600, 21600, 86400, 604800 };

	private static final String[] EXPIRY_LABELS = { "after end date", "<5 min", "5–15 min", "15–60 min", "1–6 h",
			"6–24 h", "1–7 days", ">7 days" };

	private final List<Activity> activity;

	private final List<Activity> trades;

	private final Map<String, KnownMarket> markets;

	/**
	 * @param activity the sample, of any activity type
	 * @param markets what we already know about the sample's markets, by condition id
	 */
	public SampleStats(List<Activity> activity, Map<String, KnownMarket> markets) {
		this.activity = activity;
		this.trades = activity.stream().filter(a -> "TRADE".equals(a.type())).toList();
		this.markets = markets;
	}

	public int tradeCount() {
		return trades.size();
	}

	public Long firstTradeTs() {
		return trades.stream().map(Activity::timestamp).min(Long::compare).orElse(null);
	}

	public Long lastTradeTs() {
		return trades.stream().map(Activity::timestamp).max(Long::compare).orElse(null);
	}

	/** Markets we know nothing about get their category from the slug, as unenriched markets do after a sync. */
	public List<MarketFlows> marketFlows() {
		Map<String, List<Activity>> byMarket = activity.stream()
			.filter(a -> a.conditionId() != null)
			.collect(Collectors.groupingBy(Activity::conditionId, LinkedHashMap::new, Collectors.toList()));
		return byMarket.entrySet().stream().map(e -> flows(e.getKey(), e.getValue())).toList();
	}

	private MarketFlows flows(String conditionId, List<Activity> rows) {
		double[] buyUsdc = new double[2];
		double[] buyShares = new double[2];
		double[] sellUsdc = new double[2];
		double[] sellShares = new double[2];
		double redeemUsdc = 0;
		double mergeUsdc = 0;
		double mergeShares = 0;
		double splitUsdc = 0;
		double splitShares = 0;
		long tradeCount = 0;
		for (Activity a : rows) {
			double usdc = a.usdcSize() == null ? 0 : a.usdcSize();
			double size = a.size() == null ? 0 : a.size();
			switch (a.type()) {
				case "TRADE" -> {
					tradeCount++;
					Integer o = a.outcomeIndex();
					if (o != null && (o == 0 || o == 1)) {
						if ("BUY".equals(a.side())) {
							buyUsdc[o] += usdc;
							buyShares[o] += size;
						}
						else if ("SELL".equals(a.side())) {
							sellUsdc[o] += usdc;
							sellShares[o] += size;
						}
					}
				}
				case "REDEEM" -> redeemUsdc += usdc;
				case "MERGE" -> {
					mergeUsdc += usdc;
					mergeShares += size;
				}
				case "SPLIT" -> {
					splitUsdc += usdc;
					splitShares += size;
				}
				default -> {
				}
			}
		}
		String slug = rows.stream().map(Activity::slug).filter(Objects::nonNull).findFirst().orElse(null);
		String eventSlug = rows.stream().map(Activity::eventSlug).filter(Objects::nonNull).findFirst().orElse(slug);
		String title = rows.stream().map(Activity::title).filter(Objects::nonNull).findFirst().orElse(null);
		KnownMarket known = markets.get(conditionId);
		String category = known != null && known.category() != null ? known.category()
				: MarketCategory.fromSlug(eventSlug);
		long first = rows.stream().mapToLong(Activity::timestamp).min().orElse(0);
		long last = rows.stream().mapToLong(Activity::timestamp).max().orElse(0);
		return new MarketFlows(conditionId, slug, title, category, known == null ? null : known.closed(),
				known == null ? null : known.winningOutcomeIndex(), known == null ? null : known.endDate(),
				buyUsdc[0], buyShares[0], buyUsdc[1], buyShares[1], sellUsdc[0], sellShares[0], sellUsdc[1],
				sellShares[1], redeemUsdc, mergeUsdc, mergeShares, splitUsdc, splitShares, tradeCount, first, last);
	}

	@Override
	public List<TypeTotal> typeTotals() {
		record Key(String type, String side) {
		}
		Map<Key, List<Activity>> groups = activity.stream()
			.collect(Collectors.groupingBy(a -> new Key(a.type(), a.side() == null ? "" : a.side())));
		return groups.entrySet()
			.stream()
			.map(e -> new TypeTotal(e.getKey().type(), e.getKey().side(), e.getValue().size(),
					e.getValue().stream().mapToDouble(a -> a.usdcSize() == null ? 0 : a.usdcSize()).sum()))
			.sorted(Comparator.comparing(TypeTotal::type).thenComparing(TypeTotal::side))
			.toList();
	}

	@Override
	public Timing timing() {
		long[] hours = new long[24];
		long[] weekdays = new long[7];
		for (Activity t : trades) {
			ZonedDateTime at = Instant.ofEpochSecond(t.timestamp()).atZone(ZoneOffset.UTC);
			hours[at.getHour()]++;
			weekdays[at.getDayOfWeek().getValue() - 1]++;
		}
		long activeDays = trades.stream()
			.map(t -> LocalDate.ofInstant(Instant.ofEpochSecond(t.timestamp()), ZoneOffset.UTC))
			.distinct()
			.count();
		List<Long> ts = trades.stream().map(Activity::timestamp).sorted().toList();
		List<Double> gaps = new ArrayList<>();
		for (int i = 1; i < ts.size(); i++) {
			long gap = ts.get(i) - ts.get(i - 1);
			if (gap > 0) {
				gaps.add((double) gap);
			}
		}
		return new Timing(hours, weekdays, activeDays, percentile(gaps, 0.5));
	}

	@Override
	public List<Bucket> entryPriceBuckets() {
		List<Activity> buys = trades.stream().filter(t -> "BUY".equals(t.side()) && t.price() != null).toList();
		return buckets(buys, b -> bucket(b.price(), PRICE_EDGES), PRICE_LABELS);
	}

	@Override
	public List<Bucket> timeToExpiryBuckets() {
		List<Activity> buys = buysWithEndDate();
		return buckets(buys, b -> bucket(secondsToExpiry(b), EXPIRY_EDGES), EXPIRY_LABELS);
	}

	@Override
	public Double medianSecondsToExpiry() {
		return percentile(buysWithEndDate().stream().map(b -> (double) secondsToExpiry(b)).toList(), 0.5);
	}

	@Override
	public SizePercentiles tradeSizes() {
		List<Double> sizes = trades.stream().map(Activity::usdcSize).filter(Objects::nonNull).toList();
		return new SizePercentiles(orZero(percentile(sizes, 0.10)), orZero(percentile(sizes, 0.50)),
				orZero(percentile(sizes, 0.90)), orZero(percentile(sizes, 0.99)),
				sizes.stream().mapToDouble(Double::doubleValue).average().orElse(0));
	}

	private List<Activity> buysWithEndDate() {
		return trades.stream()
			.filter(t -> "BUY".equals(t.side()) && t.conditionId() != null)
			.filter(t -> {
				KnownMarket m = markets.get(t.conditionId());
				return m != null && m.endDate() != null;
			})
			.toList();
	}

	private long secondsToExpiry(Activity buy) {
		return markets.get(buy.conditionId()).endDate().getEpochSecond() - buy.timestamp();
	}

	/** Only buckets that have trades, in range order, like the SQL GROUP BY. */
	private static List<Bucket> buckets(List<Activity> buys, Function<Activity, Integer> index, String[] labels) {
		long[] counts = new long[labels.length];
		double[] usdc = new double[labels.length];
		for (Activity b : buys) {
			int i = index.apply(b);
			counts[i]++;
			usdc[i] += b.usdcSize() == null ? 0 : b.usdcSize();
		}
		List<Bucket> result = new ArrayList<>();
		for (int i = 0; i < labels.length; i++) {
			if (counts[i] > 0) {
				result.add(new Bucket(labels[i], counts[i], usdc[i]));
			}
		}
		return result;
	}

	private static int bucket(double value, double[] edges) {
		int i = 0;
		while (i < edges.length && value >= edges[i]) {
			i++;
		}
		return i;
	}

	private static int bucket(long value, long[] edges) {
		int i = 0;
		while (i < edges.length && value >= edges[i]) {
			i++;
		}
		return i;
	}

	/** Postgres percentile_cont: linear interpolation between the closest ranks; null for no values. */
	static Double percentile(List<Double> values, double p) {
		if (values.isEmpty()) {
			return null;
		}
		List<Double> sorted = values.stream().sorted().toList();
		double pos = p * (sorted.size() - 1);
		int lo = (int) Math.floor(pos);
		int hi = (int) Math.ceil(pos);
		return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (pos - lo);
	}

	private static double orZero(Double v) {
		return v == null ? 0 : v;
	}
}
