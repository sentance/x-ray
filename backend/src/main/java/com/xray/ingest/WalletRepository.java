package com.xray.ingest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WalletRepository {

	public record Wallet(String address, String name, String pseudonym, Long newestTs, Long oldestTs,
			long activityCount, boolean historyComplete, OffsetDateTime lastSyncedAt, int syncVersion) {
	}

	private static final String COLUMNS = """
			address, name, pseudonym, newest_ts, oldest_ts, activity_count, history_complete, last_synced_at,
			sync_version""";

	private final JdbcClient jdbc;

	public WalletRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void ensureExists(String address) {
		jdbc.sql("INSERT INTO wallet (address) VALUES (?) ON CONFLICT DO NOTHING").param(address).update();
	}

	public Optional<Wallet> find(String address) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM wallet WHERE address = ?")
			.param(address)
			.query(Wallet.class)
			.optional();
	}

	public List<Wallet> recentlySynced(int limit) {
		return jdbc.sql("SELECT " + COLUMNS
				+ " FROM wallet WHERE last_synced_at IS NOT NULL ORDER BY last_synced_at DESC LIMIT ?")
			.param(limit)
			.query(Wallet.class)
			.list();
	}

	public void updateProfile(String address, String name, String pseudonym, String profileJson) {
		jdbc.sql("UPDATE wallet SET name = ?, pseudonym = ?, profile = ?::jsonb WHERE address = ?")
			.params(name, pseudonym, profileJson, address)
			.update();
	}

	public Optional<String> profileJson(String address) {
		return jdbc.sql("SELECT profile::text FROM wallet WHERE address = ? AND profile IS NOT NULL")
			.param(address)
			.query(String.class)
			.optional();
	}

	/** Records how far the activity walk has got, so an interrupted sync resumes from here. */
	public void updateCheckpoint(String address, long newestTs) {
		jdbc.sql("""
				UPDATE wallet SET newest_ts = ?,
				  oldest_ts = (SELECT min(ts) FROM activity WHERE address = ?),
				  activity_count = (SELECT count(*) FROM activity WHERE address = ?)
				WHERE address = ?""").params(newestTs, address, address, address).update();
	}

	public void markSynced(String address) {
		jdbc.sql("""
				UPDATE wallet SET history_complete = TRUE, last_synced_at = now(), sync_version = sync_version + 1
				WHERE address = ?""").param(address).update();
	}
}
