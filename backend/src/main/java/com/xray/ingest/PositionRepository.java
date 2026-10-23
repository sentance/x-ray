package com.xray.ingest;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PositionRepository {

	public record Snapshot(double openValue, String positionsJson) {
	}

	private final JdbcClient jdbc;

	public PositionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void save(String address, double openValue, String positionsJson) {
		jdbc.sql("""
				INSERT INTO position_snapshot (address, open_value, positions) VALUES (?, ?, ?::jsonb)
				ON CONFLICT (address) DO UPDATE
				SET open_value = excluded.open_value, positions = excluded.positions, fetched_at = now()""")
			.params(address, openValue, positionsJson)
			.update();
	}

	public Optional<Snapshot> find(String address) {
		return jdbc.sql("SELECT open_value, positions::text AS positions_json FROM position_snapshot WHERE address = ?")
			.param(address)
			.query(Snapshot.class)
			.optional();
	}
}
