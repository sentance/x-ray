package com.xray.ingest;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.xray.client.PolymarketDtos.PnlPoint;

@Repository
public class PnlRepository {

	/** One day of cumulative P&L figures as stored. */
	public record DailyPnl(long ts, double economicPnl, double realizedPnl, double unrealizedPnl, double fees,
			double makerRebate, double takerRebate, double rewardIncome, double volumeUsdc, long tradeCount) {
	}

	private final JdbcTemplate jdbc;

	public PnlRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void replace(String address, List<PnlPoint> points) {
		jdbc.update("DELETE FROM pnl_point WHERE address = ?", address);
		jdbc.batchUpdate("""
				INSERT INTO pnl_point (address, ts, economic_pnl, realized_pnl, unrealized_pnl, trade_pnl, fees,
				  maker_rebate, taker_rebate, reward_income, volume_usdc, trade_count)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (address, ts) DO NOTHING""", points, 500, (ps, p) -> {
			ps.setString(1, address);
			ps.setLong(2, p.timestamp());
			ps.setObject(3, p.economicPnl());
			ps.setObject(4, p.realizedPnl());
			ps.setObject(5, p.unrealizedPnl());
			ps.setObject(6, p.tradePnl());
			ps.setObject(7, p.fees());
			ps.setObject(8, p.makerRebate());
			ps.setObject(9, p.takerRebate());
			ps.setObject(10, p.rewardIncome());
			ps.setObject(11, p.volumeUsdc());
			ps.setObject(12, p.tradeCount());
		});
	}

	public List<DailyPnl> series(String address) {
		return jdbc.query("""
				SELECT ts, coalesce(economic_pnl, 0) AS economic_pnl, coalesce(realized_pnl, 0) AS realized_pnl,
				  coalesce(unrealized_pnl, 0) AS unrealized_pnl, coalesce(fees, 0) AS fees,
				  coalesce(maker_rebate, 0) AS maker_rebate, coalesce(taker_rebate, 0) AS taker_rebate,
				  coalesce(reward_income, 0) AS reward_income, coalesce(volume_usdc, 0) AS volume_usdc,
				  coalesce(trade_count, 0) AS trade_count
				FROM pnl_point WHERE address = ? ORDER BY ts""",
				(rs, i) -> new DailyPnl(rs.getLong("ts"), rs.getDouble("economic_pnl"), rs.getDouble("realized_pnl"),
						rs.getDouble("unrealized_pnl"), rs.getDouble("fees"), rs.getDouble("maker_rebate"),
						rs.getDouble("taker_rebate"), rs.getDouble("reward_income"), rs.getDouble("volume_usdc"),
						rs.getLong("trade_count")),
				address);
	}
}
