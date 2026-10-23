package com.xray.binance;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.xray.binance.BinanceClient.Kline;

/** 1-minute Binance candles, stored once and shared by every wallet's lag test. */
@Repository
public class KlineRepository {

	private final JdbcTemplate jdbc;

	public KlineRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** How many candles are stored in each chunk of {@code chunkMinutes} minutes (chunk = open minute / size). */
	public Map<Long, Long> chunkCounts(String symbol, Collection<Long> chunks, int chunkMinutes) {
		Map<Long, Long> counts = new HashMap<>();
		if (chunks.isEmpty()) {
			return counts;
		}
		jdbc.query("""
				SELECT open_time / 60000 / ? AS chunk, count(*) AS n FROM binance_kline
				WHERE symbol = ? AND open_time / 60000 / ? = ANY(?) GROUP BY 1""", ps -> {
			ps.setInt(1, chunkMinutes);
			ps.setString(2, symbol);
			ps.setInt(3, chunkMinutes);
			ps.setArray(4, ps.getConnection().createArrayOf("bigint", chunks.toArray()));
		}, rs -> {
			counts.put(rs.getLong("chunk"), rs.getLong("n"));
		});
		return counts;
	}

	public void save(String symbol, List<Kline> klines) {
		jdbc.batchUpdate("""
				INSERT INTO binance_kline (symbol, open_time, open, close) VALUES (?, ?, ?, ?)
				ON CONFLICT (symbol, open_time) DO UPDATE SET open = excluded.open, close = excluded.close""", klines,
				1000, (ps, k) -> {
					ps.setString(1, symbol);
					ps.setLong(2, k.openTime());
					ps.setBigDecimal(3, k.open());
					ps.setBigDecimal(4, k.close());
				});
	}

	/** Opening price of each stored minute in [fromMs, toMs], keyed by the minute's epoch ms. */
	public Map<Long, Double> opens(String symbol, long fromMs, long toMs) {
		Map<Long, Double> opens = new HashMap<>();
		jdbc.query("SELECT open_time, open FROM binance_kline WHERE symbol = ? AND open_time BETWEEN ? AND ?",
				rs -> {
					opens.put(rs.getLong("open_time"), rs.getDouble("open"));
				}, symbol, fromMs, toMs);
		return opens;
	}
}
