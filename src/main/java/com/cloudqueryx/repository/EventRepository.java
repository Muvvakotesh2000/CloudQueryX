package com.cloudqueryx.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public class EventRepository {

    private static final Logger log = LoggerFactory.getLogger(EventRepository.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final DatabaseConfig db;

    public EventRepository(DatabaseConfig db) {
        this.db = db;
    }

    public void insert(String databaseId, EventRow event) {
        String sql = """
                INSERT INTO events (id, database_id, user_id, event_type, action, properties, session_id)
                VALUES (?, ?::uuid, ?, ?, ?, ?::jsonb, ?)
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, event.id);
            ps.setString(2, databaseId);
            ps.setString(3, event.userId);
            ps.setString(4, event.eventType);
            ps.setString(5, event.action);
            ps.setString(6, toJson(event.properties));
            ps.setString(7, event.sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert event", e);
        }
    }

    public List<EventRow> queryByUser(String databaseId, String userId, int limit) {
        String sql = """
                SELECT * FROM events
                WHERE database_id = ?::uuid AND user_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, userId);
            ps.setInt(3, limit);
            return readEvents(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query events by user", e);
        }
    }

    public List<EventRow> queryByType(String databaseId, String eventType, int limit) {
        String sql = """
                SELECT * FROM events
                WHERE database_id = ?::uuid AND event_type = ?
                ORDER BY created_at DESC
                LIMIT ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, eventType);
            ps.setInt(3, limit);
            return readEvents(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query events by type", e);
        }
    }

    public List<EventRow> queryByTimeRange(String databaseId, Instant from, Instant to) {
        String sql = """
                SELECT * FROM events
                WHERE database_id = ?::uuid AND created_at >= ? AND created_at <= ?
                ORDER BY created_at
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setTimestamp(2, Timestamp.from(from));
            ps.setTimestamp(3, Timestamp.from(to));
            return readEvents(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query events by time range", e);
        }
    }

    public Map<String, Long> getEventTypeCounts(String databaseId, String userId) {
        String sql = """
                SELECT event_type, COUNT(*) AS cnt FROM events
                WHERE database_id = ?::uuid AND user_id = ?
                GROUP BY event_type
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, userId);
            ResultSet rs = ps.executeQuery();
            Map<String, Long> counts = new LinkedHashMap<>();
            while (rs.next()) {
                counts.put(rs.getString("event_type"), rs.getLong("cnt"));
            }
            return counts;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get event type counts", e);
        }
    }

    private List<EventRow> readEvents(ResultSet rs) throws SQLException {
        List<EventRow> rows = new ArrayList<>();
        while (rs.next()) {
            rows.add(new EventRow(
                    rs.getString("id"),
                    rs.getString("user_id"),
                    rs.getString("event_type"),
                    rs.getString("action"),
                    parseJson(rs.getString("properties")),
                    rs.getString("session_id"),
                    rs.getTimestamp("created_at").toInstant()
            ));
        }
        return rows;
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        try {
            return mapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    public record EventRow(
            String id, String userId, String eventType, String action,
            Map<String, Object> properties, String sessionId, Instant createdAt
    ) {}
}
