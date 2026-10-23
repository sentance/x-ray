package com.xray.market;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MarketRepository {

	/** What we know about a market from earlier enrichment; any field may still be null. */
	public record KnownMarket(String conditionId, String category, Instant endDate, Boolean closed,
			Integer winningOutcomeIndex) {
	}

	public record MarketUpdate(String conditionId, String category, List<String> tags, Instant endDate, Boolean closed,
			Integer winningOutcomeIndex, Boolean negRisk) {
	}

	private final JdbcTemplate jdbc;

	public MarketRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Map<String, KnownMarket> find(Collection<String> conditionIds) {
		if (conditionIds.isEmpty()) {
			return Map.of();
		}
		return jdbc.query("""
				SELECT condition_id, category, end_date, closed, winning_outcome_index FROM market
				WHERE condition_id = ANY(?)""",
				ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", conditionIds.toArray())), (rs, i) -> {
					Timestamp end = rs.getTimestamp("end_date");
					return new KnownMarket(rs.getString("condition_id"), rs.getString("category"),
							end == null ? null : end.toInstant(), (Boolean) rs.getObject("closed"),
							(Integer) rs.getObject("winning_outcome_index"));
				})
			.stream()
			.collect(Collectors.toMap(KnownMarket::conditionId, Function.identity()));
	}

	/** Creates placeholder rows for every market the wallet traded that we have not seen yet. */
	public void insertMissingFromActivity(String address) {
		jdbc.update("""
				INSERT INTO market (condition_id, slug, event_slug, title)
				SELECT DISTINCT ON (condition_id) condition_id, slug, event_slug, title
				FROM activity WHERE address = ? AND condition_id IS NOT NULL
				ORDER BY condition_id, ts DESC
				ON CONFLICT DO NOTHING""", address);
	}

	/**
	 * Event slugs of this wallet's markets that were never enriched, or that were open last time and should have
	 * resolved by now.
	 */
	public List<String> eventSlugsToEnrich(String address) {
		return jdbc.queryForList("""
				SELECT DISTINCT m.event_slug FROM market m
				WHERE m.event_slug IS NOT NULL
				  AND m.condition_id IN (SELECT DISTINCT condition_id FROM activity WHERE address = ?)
				  AND (m.enriched_at IS NULL
				       OR (m.closed IS NOT TRUE AND m.end_date < now() - interval '1 hour'
				           AND m.enriched_at < now() - interval '1 hour'))""", String.class, address);
	}

	public void update(List<MarketUpdate> updates) {
		jdbc.batchUpdate("""
				UPDATE market SET category = ?, tags = ?, end_date = ?, closed = ?, winning_outcome_index = ?,
				  neg_risk = ?, enriched_at = now()
				WHERE condition_id = ?""", updates, 500, (ps, u) -> {
			ps.setString(1, u.category());
			ps.setArray(2, ps.getConnection().createArrayOf("text", u.tags().toArray()));
			ps.setTimestamp(3, u.endDate() == null ? null : Timestamp.from(u.endDate()));
			ps.setObject(4, u.closed());
			ps.setObject(5, u.winningOutcomeIndex());
			ps.setObject(6, u.negRisk());
			ps.setString(7, u.conditionId());
		});
	}

	/** Gives markets Gamma did not return a category from their slug, so they are not fetched again. */
	public void markUnresolvedEvents(List<String> eventSlugs) {
		for (String slug : eventSlugs) {
			jdbc.update("""
					UPDATE market SET category = coalesce(category, ?), tags = coalesce(tags, '{}'),
					  enriched_at = now()
					WHERE event_slug = ? AND enriched_at IS NULL""", MarketCategory.fromSlug(slug), slug);
		}
	}
}
