package com.xray.analysis;

import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** Stores computed reports per wallet sync version, so each report is computed once per sync. */
@Component
public class AnalysisCache {

	private final JdbcClient jdbc;

	private final JsonMapper json;

	public AnalysisCache(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	public <T> T get(String address, String kind, int syncVersion, Class<T> type, Supplier<T> compute) {
		return find(address, kind, syncVersion, type).orElseGet(() -> {
			T value = compute.get();
			put(address, kind, syncVersion, value);
			return value;
		});
	}

	public void put(String address, String kind, int syncVersion, Object value) {
		jdbc.sql("""
				INSERT INTO analysis_cache (address, kind, sync_version, payload) VALUES (?, ?, ?, ?::jsonb)
				ON CONFLICT (address, kind) DO UPDATE
				SET sync_version = excluded.sync_version, payload = excluded.payload, created_at = now()""")
			.params(address, kind, syncVersion, json.writeValueAsString(value))
			.update();
	}

	public <T> Optional<T> find(String address, String kind, int syncVersion, Class<T> type) {
		return jdbc
			.sql("SELECT payload::text FROM analysis_cache WHERE address = ? AND kind = ? AND sync_version = ?")
			.params(address, kind, syncVersion)
			.query(String.class)
			.optional()
			.map(s -> json.readValue(s, type));
	}
}
