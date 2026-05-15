package com.cloudqueryx.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public class SourceRepository {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final DatabaseConfig db;

    public SourceRepository(DatabaseConfig db) {
        this.db = db;
    }

    public void upsert(SourceRow row) {
        String sql = """
                INSERT INTO sources (id, database_id, user_id, source_type, source_name,
                    content, content_hash, metadata, version, status)
                VALUES (?, ?::uuid, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (database_id, id)
                DO UPDATE SET source_type = EXCLUDED.source_type,
                              source_name = EXCLUDED.source_name,
                              content = EXCLUDED.content,
                              content_hash = EXCLUDED.content_hash,
                              metadata = EXCLUDED.metadata,
                              version = sources.version + 1,
                              status = EXCLUDED.status,
                              updated_at = now()
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, row.id());
            ps.setString(2, row.databaseId());
            ps.setString(3, row.userId());
            ps.setString(4, row.sourceType());
            ps.setString(5, row.sourceName());
            ps.setString(6, row.content());
            ps.setString(7, row.contentHash());
            ps.setString(8, toJson(row.metadata()));
            ps.setInt(9, row.version());
            ps.setString(10, row.status());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert source", e);
        }
    }

    public Optional<SourceRow> get(String databaseId, String sourceId) {
        String sql = """
                SELECT * FROM sources
                WHERE database_id = ?::uuid AND id = ? AND status <> 'DELETED'
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, sourceId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(row(rs));
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get source", e);
        }
    }

    public List<SourceRow> list(String databaseId, String userId, int limit) {
        String sql = """
                SELECT * FROM sources
                WHERE database_id = ?::uuid AND user_id = ? AND status <> 'DELETED'
                ORDER BY updated_at DESC
                LIMIT ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, userId);
            ps.setInt(3, limit);
            ResultSet rs = ps.executeQuery();
            List<SourceRow> rows = new ArrayList<>();
            while (rs.next()) rows.add(row(rs));
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list sources", e);
        }
    }

    private SourceRow row(ResultSet rs) throws SQLException {
        return new SourceRow(
                rs.getString("id"),
                rs.getString("database_id"),
                rs.getString("user_id"),
                rs.getString("source_type"),
                rs.getString("source_name"),
                rs.getString("content"),
                rs.getString("content_hash"),
                parseJson(rs.getString("metadata")),
                rs.getInt("version"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
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

    public record SourceRow(
            String id,
            String databaseId,
            String userId,
            String sourceType,
            String sourceName,
            String content,
            String contentHash,
            Map<String, Object> metadata,
            int version,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
