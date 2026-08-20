package com.cloudqueryx.repository;

import com.cloudqueryx.context.runtime.TraceEvent;
import com.cloudqueryx.web.api.JsonUtil;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public class TraceEventRepository {
    private final DatabaseConfig db;

    public TraceEventRepository(DatabaseConfig db) {
        this.db = db;
    }

    public void save(String databaseId, TraceEvent event) {
        ensureTable();
        String sql = """
                INSERT INTO context_trace_events
                    (id, database_id, request_id, bundle_id, stage, payload, created_at)
                VALUES (?, ?::uuid, ?, ?, ?, ?::jsonb, ?)
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, databaseId);
            ps.setString(3, event.requestId());
            ps.setString(4, event.bundleId());
            ps.setString(5, event.stage());
            ps.setString(6, JsonUtil.toJson(event.payload()));
            ps.setTimestamp(7, Timestamp.from(event.timestamp()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save trace event", e);
        }
    }

    public void attachBundle(String databaseId, String requestId, String bundleId) {
        ensureTable();
        String sql = "UPDATE context_trace_events SET bundle_id = ? WHERE database_id = ?::uuid AND request_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, bundleId);
            ps.setString(2, databaseId);
            ps.setString(3, requestId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to attach bundle to trace", e);
        }
    }

    public List<TraceEvent> listByRequest(String databaseId, String requestId) {
        ensureTable();
        return list(databaseId, "request_id = ?", requestId);
    }

    public List<TraceEvent> listByBundle(String databaseId, String bundleId) {
        ensureTable();
        return list(databaseId, "bundle_id = ?", bundleId);
    }

    private List<TraceEvent> list(String databaseId, String predicate, String value) {
        String sql = "SELECT * FROM context_trace_events WHERE database_id = ?::uuid AND " + predicate + " ORDER BY created_at ASC";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, value);
            ResultSet rs = ps.executeQuery();
            List<TraceEvent> events = new ArrayList<>();
            while (rs.next()) events.add(row(rs));
            return events;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load trace events", e);
        }
    }

    private TraceEvent row(ResultSet rs) throws SQLException {
        Map<String, Object> payload = JsonUtil.parseString(rs.getString("payload"));
        Timestamp ts = rs.getTimestamp("created_at");
        return new TraceEvent(
                rs.getString("request_id"),
                rs.getString("bundle_id"),
                rs.getString("stage"),
                payload,
                ts == null ? Instant.now() : ts.toInstant()
        );
    }

    private void ensureTable() {
        String sql = """
                CREATE TABLE IF NOT EXISTS context_trace_events (
                    id TEXT PRIMARY KEY,
                    database_id UUID REFERENCES databases(id) ON DELETE CASCADE,
                    request_id TEXT NOT NULL,
                    bundle_id TEXT,
                    stage TEXT NOT NULL,
                    payload JSONB DEFAULT '{}',
                    created_at TIMESTAMPTZ DEFAULT now()
                )
                """;
        try (Connection conn = db.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
            st.execute("CREATE INDEX IF NOT EXISTS idx_trace_events_request ON context_trace_events (database_id, request_id, created_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_trace_events_bundle ON context_trace_events (database_id, bundle_id, created_at)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to ensure trace event table", e);
        }
    }
}
