package com.xray.ingest;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SyncJobRepository {

	public enum Status {
		RUNNING, DONE, FAILED
	}

	public record SyncJob(long id, String address, Status status, String phase, long fetched, Long expected,
			OffsetDateTime startedAt, OffsetDateTime finishedAt, String error) {
	}

	private static final String COLUMNS = "id, address, status, phase, fetched, expected, started_at, finished_at, error";

	private final JdbcClient jdbc;

	public SyncJobRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public SyncJob create(String address) {
		long id = jdbc.sql("INSERT INTO sync_job (address, status, phase) VALUES (?, 'RUNNING', 'QUEUED') RETURNING id")
			.param(address)
			.query(Long.class)
			.single();
		return find(id).orElseThrow();
	}

	public Optional<SyncJob> find(long id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM sync_job WHERE id = ?").param(id).query(SyncJob.class).optional();
	}

	public Optional<SyncJob> latest(String address) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM sync_job WHERE address = ? ORDER BY id DESC LIMIT 1")
			.param(address)
			.query(SyncJob.class)
			.optional();
	}

	public void phase(long id, String phase) {
		jdbc.sql("UPDATE sync_job SET phase = ? WHERE id = ?").params(phase, id).update();
	}

	public void progress(long id, long fetched, Long expected) {
		jdbc.sql("UPDATE sync_job SET fetched = ?, expected = coalesce(?, expected) WHERE id = ?")
			.params(fetched, expected, id)
			.update();
	}

	public void finish(long id, Status status, String error) {
		jdbc.sql("UPDATE sync_job SET status = ?, phase = NULL, error = ?, finished_at = now() WHERE id = ?")
			.params(status.name(), error, id)
			.update();
	}

	/** Jobs left RUNNING by a previous process can never finish. */
	public int failInterrupted() {
		return jdbc.sql("""
				UPDATE sync_job SET status = 'FAILED', error = 'Interrupted by restart', finished_at = now()
				WHERE status = 'RUNNING'""").update();
	}
}
