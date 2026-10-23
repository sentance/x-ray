package com.xray.leaderboard;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.xray.analysis.StrategyClassifier.Classification;
import com.xray.analysis.StrategyClassifier.Label;

import tools.jackson.databind.json.JsonMapper;

/** Strategy tags for leaderboard rows: from a wallet's full analysis when cached, else from a quick sample. */
@Repository
public class TagRepository {

	/** A classification made from a sample of recent activity rather than the full history. */
	public record QuickTag(String summary, List<Label> labels, int sampleTrades, Long sampleFromTs, Long sampleToTs) {
	}

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	public TagRepository(JdbcTemplate jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** Classifications from cached strategy reports that match the wallet's current sync version. */
	public Map<String, Classification> fullClassifications(Collection<String> addresses) {
		Map<String, Classification> result = new HashMap<>();
		jdbc.query("""
				SELECT c.address, (c.payload -> 'classification')::text AS classification
				FROM analysis_cache c JOIN wallet w ON w.address = c.address AND w.sync_version = c.sync_version
				WHERE c.kind = 'strategy' AND c.address = ANY(?)""", ps -> ps.setArray(1, array(ps, addresses)),
				rs -> {
					result.put(rs.getString("address"),
							json.readValue(rs.getString("classification"), Classification.class));
				});
		return result;
	}

	public Map<String, QuickTag> quickTags(Collection<String> addresses, Duration maxAge) {
		Map<String, QuickTag> result = new HashMap<>();
		jdbc.query("""
				SELECT address, payload::text AS payload FROM quick_tag
				WHERE address = ANY(?) AND computed_at > now() - make_interval(secs => ?)""", ps -> {
			ps.setArray(1, array(ps, addresses));
			ps.setLong(2, maxAge.toSeconds());
		}, rs -> {
			result.put(rs.getString("address"), json.readValue(rs.getString("payload"), QuickTag.class));
		});
		return result;
	}

	public void saveQuickTag(String address, QuickTag tag) {
		jdbc.update("""
				INSERT INTO quick_tag (address, payload) VALUES (?, ?::jsonb)
				ON CONFLICT (address) DO UPDATE SET payload = excluded.payload, computed_at = now()""", address,
				json.writeValueAsString(tag));
	}

	private static Array array(PreparedStatement ps, Collection<String> values) throws SQLException {
		return ps.getConnection().createArrayOf("text", values.toArray());
	}
}
