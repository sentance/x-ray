package com.xray.ai;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Claude's strategy explanation per wallet, kept until the wallet's next sync. */
@Repository
public class NarrativeRepository {

	private final JdbcClient jdbc;

	public NarrativeRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<String> find(String address, int syncVersion) {
		return jdbc.sql("SELECT text FROM ai_narrative WHERE address = ? AND sync_version = ?")
			.params(address, syncVersion)
			.query(String.class)
			.optional();
	}

	public void save(String address, int syncVersion, String text) {
		jdbc.sql("""
				INSERT INTO ai_narrative (address, sync_version, text) VALUES (?, ?, ?)
				ON CONFLICT (address) DO UPDATE
				SET sync_version = excluded.sync_version, text = excluded.text, created_at = now()""")
			.params(address, syncVersion, text)
			.update();
	}
}
