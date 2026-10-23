package com.xray.analysis;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Aggregations over a wallet's stored activity, pushed into SQL because wallets can have 500k+ rows. */
@Repository
public class ActivityStatsRepository {

	/** Everything the wallet did in one market (condition), split by outcome 0 and 1. */
	public record MarketFlows(String conditionId, String slug, String title, String category, Boolean closed,
			Integer winningOutcome, Instant endDate, double buyUsdc0, double buyShares0, double buyUsdc1,
			double buyShares1, double sellUsdc0, double sellShares0, double sellUsdc1, double sellShares1,
			double redeemUsdc, double mergeUsdc, double mergeShares, double splitUsdc, double splitShares, long trades,
			long firstTs, long lastTs) {

		public double buyUsdc() {
			return buyUsdc0 + buyUsdc1;
		}

		public double sellUsdc() {
			return sellUsdc0 + sellUsdc1;
		}

		public double boughtShares() {
			return buyShares0 + buyShares1;
		}

		public double soldShares() {
			return sellShares0 + sellShares1;
		}
	}

	public record TypeTotal(String type, String side, long count, double usdc) {
	}

	public record Bucket(String label, long count, double usdc) {
	}

	public record SizePercentiles(double p10, double p50, double p90, double p99, double mean) {
	}

	public record Timing(long[] hourOfDay, long[] dayOfWeek, long activeDays, Double medianGapSeconds) {
	}

	private final JdbcTemplate jdbc;

	public ActivityStatsRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** The wallet's full-history aggregates; each one runs its query when called. */
	public ActivityStats forWallet(String address) {
		return new ActivityStats() {
			@Override
			public List<TypeTotal> typeTotals() {
				return ActivityStatsRepository.this.typeTotals(address);
			}

			@Override
			public Timing timing() {
				return ActivityStatsRepository.this.timing(address);
			}

			@Override
			public List<Bucket> entryPriceBuckets() {
				return ActivityStatsRepository.this.entryPriceBuckets(address);
			}

			@Override
			public List<Bucket> timeToExpiryBuckets() {
				return ActivityStatsRepository.this.timeToExpiryBuckets(address);
			}

			@Override
			public Double medianSecondsToExpiry() {
				return ActivityStatsRepository.this.medianSecondsToExpiry(address);
			}

			@Override
			public SizePercentiles tradeSizes() {
				return ActivityStatsRepository.this.tradeSizes(address);
			}
		};
	}

	public List<MarketFlows> marketFlows(String address) {
		return jdbc.query("""
				SELECT a.condition_id, max(a.slug) AS slug, max(a.title) AS title, m.category, m.closed,
				  m.winning_outcome_index, m.end_date,
				  coalesce(sum(a.usdc_size) FILTER (WHERE a.type = 'TRADE' AND a.side = 'BUY' AND a.outcome_index = 0), 0) AS buy_usdc0,
				  coalesce(sum(a.size) FILTER (WHERE a.type = 'TRADE' AND a.side = 'BUY' AND a.outcome_index = 0), 0) AS buy_shares0,
				  coalesce(sum(a.usdc_size) FILTER (WHERE a.type = 'TRADE' AND a.side = 'BUY' AND a.outcome_index = 1), 0) AS buy_usdc1,
				  coalesce(sum(a.size) FILTER (WHERE a.type = 'TRADE' AND a.side = 'BUY' AND a.outcome_index = 1), 0) AS buy_shares1,
				  coalesce(sum(a.usdc_size) FILTER (WHERE a.type = 'TRADE' AND a.side = 'SELL' AND a.outcome_index = 0), 0) AS sell_usdc0,
				  coalesce(sum(a.size) FILTER (WHERE a.type = 'TRADE' AND a.side = 'SELL' AND a.outcome_index = 0), 0) AS sell_shares0,
				  coalesce(sum(a.usdc_size) FILTER (WHERE a.type = 'TRADE' AND a.side = 'SELL' AND a.outcome_index = 1), 0) AS sell_usdc1,
				  coalesce(sum(a.size) FILTER (WHERE a.type = 'TRADE' AND a.side = 'SELL' AND a.outcome_index = 1), 0) AS sell_shares1,
				  coalesce(sum(a.usdc_size) FILTER (WHERE a.type = 'REDEEM'), 0) AS redeem_usdc,
				  coalesce(sum(a.usdc_size) FILTER (WHERE a.type = 'MERGE'), 0) AS merge_usdc,
				  coalesce(sum(a.size) FILTER (WHERE a.type = 'MERGE'), 0) AS merge_shares,
				  coalesce(sum(a.usdc_size) FILTER (WHERE a.type = 'SPLIT'), 0) AS split_usdc,
				  coalesce(sum(a.size) FILTER (WHERE a.type = 'SPLIT'), 0) AS split_shares,
				  count(*) FILTER (WHERE a.type = 'TRADE') AS trades, min(a.ts) AS first_ts, max(a.ts) AS last_ts
				FROM activity a LEFT JOIN market m ON m.condition_id = a.condition_id
				WHERE a.address = ? AND a.condition_id IS NOT NULL
				GROUP BY a.condition_id, m.category, m.closed, m.winning_outcome_index, m.end_date""",
				ActivityStatsRepository::mapFlows, address);
	}

	private static MarketFlows mapFlows(ResultSet rs, int i) throws SQLException {
		Timestamp end = rs.getTimestamp("end_date");
		return new MarketFlows(rs.getString("condition_id"), rs.getString("slug"), rs.getString("title"),
				rs.getString("category"), (Boolean) rs.getObject("closed"),
				(Integer) rs.getObject("winning_outcome_index"), end == null ? null : end.toInstant(),
				rs.getDouble("buy_usdc0"), rs.getDouble("buy_shares0"), rs.getDouble("buy_usdc1"),
				rs.getDouble("buy_shares1"), rs.getDouble("sell_usdc0"), rs.getDouble("sell_shares0"),
				rs.getDouble("sell_usdc1"), rs.getDouble("sell_shares1"), rs.getDouble("redeem_usdc"),
				rs.getDouble("merge_usdc"), rs.getDouble("merge_shares"), rs.getDouble("split_usdc"),
				rs.getDouble("split_shares"), rs.getLong("trades"), rs.getLong("first_ts"), rs.getLong("last_ts"));
	}

	public List<TypeTotal> typeTotals(String address) {
		return jdbc.query("""
				SELECT type, coalesce(side, '') AS side, count(*) AS count, coalesce(sum(usdc_size), 0) AS usdc
				FROM activity WHERE address = ? GROUP BY type, side ORDER BY type, side""",
				(rs, i) -> new TypeTotal(rs.getString("type"), rs.getString("side"), rs.getLong("count"),
						rs.getDouble("usdc")),
				address);
	}

	/** Buy volume by entry price. */
	public List<Bucket> entryPriceBuckets(String address) {
		return jdbc.query("""
				SELECT CASE
				    WHEN price < 0.10 THEN '<0.10'
				    WHEN price < 0.30 THEN '0.10–0.30'
				    WHEN price < 0.50 THEN '0.30–0.50'
				    WHEN price < 0.70 THEN '0.50–0.70'
				    WHEN price < 0.90 THEN '0.70–0.90'
				    WHEN price < 0.97 THEN '0.90–0.97'
				    ELSE '≥0.97' END AS label,
				  min(price) AS lo, count(*) AS count, sum(usdc_size) AS usdc
				FROM activity WHERE address = ? AND type = 'TRADE' AND side = 'BUY'
				GROUP BY 1 ORDER BY lo""",
				(rs, i) -> new Bucket(rs.getString("label"), rs.getLong("count"), rs.getDouble("usdc")), address);
	}

	/** Buy volume by how long before the market's end date it was placed. */
	public List<Bucket> timeToExpiryBuckets(String address) {
		return jdbc.query("""
				WITH t AS (
				  SELECT a.usdc_size, extract(epoch FROM m.end_date) - a.ts AS secs
				  FROM activity a JOIN market m ON m.condition_id = a.condition_id
				  WHERE a.address = ? AND a.type = 'TRADE' AND a.side = 'BUY' AND m.end_date IS NOT NULL)
				SELECT CASE
				    WHEN secs < 0 THEN 'after end date'
				    WHEN secs < 300 THEN '<5 min'
				    WHEN secs < 900 THEN '5–15 min'
				    WHEN secs < 3600 THEN '15–60 min'
				    WHEN secs < 21600 THEN '1–6 h'
				    WHEN secs < 86400 THEN '6–24 h'
				    WHEN secs < 604800 THEN '1–7 days'
				    ELSE '>7 days' END AS label,
				  min(secs) AS lo, count(*) AS count, sum(usdc_size) AS usdc
				FROM t GROUP BY 1 ORDER BY lo""",
				(rs, i) -> new Bucket(rs.getString("label"), rs.getLong("count"), rs.getDouble("usdc")), address);
	}

	public Double medianSecondsToExpiry(String address) {
		return jdbc.queryForObject("""
				SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM m.end_date) - a.ts)
				FROM activity a JOIN market m ON m.condition_id = a.condition_id
				WHERE a.address = ? AND a.type = 'TRADE' AND a.side = 'BUY' AND m.end_date IS NOT NULL""",
				Double.class, address);
	}

	public SizePercentiles tradeSizes(String address) {
		return jdbc.queryForObject("""
				SELECT coalesce(percentile_cont(0.10) WITHIN GROUP (ORDER BY usdc_size), 0) AS p10,
				  coalesce(percentile_cont(0.50) WITHIN GROUP (ORDER BY usdc_size), 0) AS p50,
				  coalesce(percentile_cont(0.90) WITHIN GROUP (ORDER BY usdc_size), 0) AS p90,
				  coalesce(percentile_cont(0.99) WITHIN GROUP (ORDER BY usdc_size), 0) AS p99,
				  coalesce(avg(usdc_size), 0) AS mean
				FROM activity WHERE address = ? AND type = 'TRADE'""",
				(rs, i) -> new SizePercentiles(rs.getDouble("p10"), rs.getDouble("p50"), rs.getDouble("p90"),
						rs.getDouble("p99"), rs.getDouble("mean")),
				address);
	}

	public Timing timing(String address) {
		long[] hours = new long[24];
		long[] weekdays = new long[7];
		jdbc.query("""
				SELECT extract(hour FROM to_timestamp(ts) AT TIME ZONE 'UTC')::int AS h,
				  extract(isodow FROM to_timestamp(ts) AT TIME ZONE 'UTC')::int AS d, count(*) AS c
				FROM activity WHERE address = ? AND type = 'TRADE' GROUP BY 1, 2""", rs -> {
			hours[rs.getInt("h")] += rs.getLong("c");
			weekdays[rs.getInt("d") - 1] += rs.getLong("c");
		}, address);
		Long activeDays = jdbc.queryForObject("""
				SELECT count(DISTINCT (to_timestamp(ts) AT TIME ZONE 'UTC')::date)
				FROM activity WHERE address = ? AND type = 'TRADE'""", Long.class, address);
		Double medianGap = jdbc.queryForObject("""
				SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY gap) FROM (
				  SELECT ts - lag(ts) OVER (ORDER BY ts) AS gap FROM activity WHERE address = ? AND type = 'TRADE') g
				WHERE gap > 0""", Double.class, address);
		return new Timing(hours, weekdays, activeDays == null ? 0 : activeDays, medianGap);
	}
}
