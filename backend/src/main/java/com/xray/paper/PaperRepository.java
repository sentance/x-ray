package com.xray.paper;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import static com.xray.paper.PaperModel.*;

@Repository
public class PaperRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    public PaperRepository(JdbcClient jdbc, JsonMapper json) { this.jdbc = jdbc; this.json = json; }
    public Optional<State> find(UUID id) {
        return jdbc.sql("SELECT state::text FROM paper_session WHERE id = ?").param(id).query(String.class)
                .optional().map(s -> json.readValue(s, State.class));
    }
    @Transactional
    public void save(State state, List<Evidence> evidence) {
        jdbc.sql("""
                INSERT INTO paper_session(id, state) VALUES (?, ?::jsonb)
                ON CONFLICT(id) DO UPDATE SET state = excluded.state, updated_at = now()
                """).params(state.id, json.writeValueAsString(state)).update();
        for (Evidence e : evidence)
            jdbc.sql("INSERT INTO paper_evidence(session_id, received_at, type, payload) VALUES (?, ?, ?, ?::jsonb)")
                    .params(state.id, e.receivedAt(), e.type(), json.writeValueAsString(e.payload())).update();
    }
    public record Row(long seq, long receivedAt, String type, String payload) { }
    public List<Row> evidence(UUID id, long after, int limit) {
        return jdbc.sql("""
                SELECT seq, received_at, type, payload::text FROM paper_evidence
                WHERE session_id = ? AND seq > ? ORDER BY seq LIMIT ?
                """).params(id, after, limit).query(Row.class).list();
    }
}
