package com.xray.ingest;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.xray.client.PolymarketDtos.Activity;

@Repository
public class ActivityRepository {

	/** An activity row ready to insert, with its {@link DedupeWindow dedupe key}. */
	public record KeyedActivity(Activity activity, String dedupeKey) {
	}

	private final JdbcTemplate jdbc;

	public ActivityRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Inserts rows that are not stored yet; returns how many were new. */
	public int insert(String address, List<KeyedActivity> rows) {
		int[][] counts = jdbc.batchUpdate("""
				INSERT INTO activity (address, ts, type, condition_id, asset, side, size, usdc_size, price, outcome,
				  outcome_index, tx_hash, slug, event_slug, title, dedupe_key)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (address, dedupe_key) DO NOTHING""", rows, 500, (ps, row) -> {
			Activity a = row.activity();
			ps.setString(1, address);
			ps.setLong(2, a.timestamp());
			ps.setString(3, a.type());
			ps.setString(4, emptyToNull(a.conditionId()));
			ps.setString(5, emptyToNull(a.asset()));
			ps.setString(6, emptyToNull(a.side()));
			ps.setObject(7, a.size());
			ps.setObject(8, a.usdcSize());
			ps.setObject(9, a.price());
			ps.setString(10, emptyToNull(a.outcome()));
			ps.setObject(11, a.outcomeIndex());
			ps.setString(12, a.transactionHash());
			ps.setString(13, emptyToNull(a.slug()));
			ps.setString(14, emptyToNull(a.eventSlug()));
			ps.setString(15, emptyToNull(a.title()));
			ps.setString(16, row.dedupeKey());
		});
		int inserted = 0;
		for (int[] batch : counts) {
			for (int c : batch) {
				inserted += Math.max(c, 0);
			}
		}
		return inserted;
	}

	private static String emptyToNull(String s) {
		return s == null || s.isEmpty() ? null : s;
	}
}
