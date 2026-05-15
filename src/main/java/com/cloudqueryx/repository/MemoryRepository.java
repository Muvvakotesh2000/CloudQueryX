package com.cloudqueryx.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public class MemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(MemoryRepository.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final DatabaseConfig db;

    public MemoryRepository(DatabaseConfig db) {
        this.db = db;
    }

    public void upsert(String databaseId, MemoryRow row) {
        String sql = """
                INSERT INTO memories (id, database_id, user_id, namespace, memory_type, content,
                    metadata, embedding, importance, confidence, recency, source,
                    valid_from, valid_until, scope)
                VALUES (?, ?::uuid, ?, ?, ?, ?, ?::jsonb, ?::vector, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (database_id, id)
                DO UPDATE SET content = EXCLUDED.content,
                              metadata = EXCLUDED.metadata,
                              embedding = EXCLUDED.embedding,
                              importance = EXCLUDED.importance,
                              confidence = EXCLUDED.confidence,
                              recency = EXCLUDED.recency,
                              valid_until = EXCLUDED.valid_until,
                              scope = EXCLUDED.scope,
                              updated_at = now()
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, row.id);
            ps.setString(2, databaseId);
            ps.setString(3, row.userId);
            ps.setString(4, row.namespace);
            ps.setString(5, row.memoryType);
            ps.setString(6, row.content);
            ps.setString(7, toJson(row.metadata));
            ps.setString(8, row.embedding != null ? toVectorString(row.embedding) : null);
            ps.setDouble(9, row.importance);
            ps.setDouble(10, row.confidence);
            ps.setDouble(11, row.recency);
            ps.setString(12, row.source);
            ps.setTimestamp(13, row.validFrom != null ? Timestamp.from(row.validFrom) : null);
            ps.setTimestamp(14, row.validUntil != null ? Timestamp.from(row.validUntil) : null);
            ps.setString(15, row.scope != null ? row.scope : "user");
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert memory", e);
        }
    }

    public Optional<MemoryRow> getById(String databaseId, String id) {
        String sql = """
                SELECT * FROM memories
                WHERE database_id = ?::uuid AND id = ?
                  AND (expired_at IS NULL)
                  AND (valid_until IS NULL OR valid_until > now())
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(rowFromResultSet(rs));
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get memory", e);
        }
    }

    public List<MemorySearchRow> searchSimilar(String databaseId, String namespace,
                                                 float[] queryEmbedding, int topK,
                                                 String memoryTypeFilter, String userIdFilter) {
        return searchSimilar(databaseId, namespace, queryEmbedding, topK,
                memoryTypeFilter, userIdFilter, null, null);
    }

    public List<MemorySearchRow> searchSimilar(String databaseId, String namespace,
                                                 float[] queryEmbedding, int topK,
                                                 String memoryTypeFilter, String userIdFilter,
                                                 String scopeFilter, Instant asOf) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, user_id, namespace, memory_type, content, metadata,
                       importance, confidence, recency, source, access_count,
                       valid_from, valid_until, created_at, updated_at, scope,
                       1 - (embedding <=> ?::vector) AS similarity
                FROM memories
                WHERE database_id = ?::uuid
                  AND namespace = ?
                  AND expired_at IS NULL
                  AND embedding IS NOT NULL
                  AND (valid_until IS NULL OR valid_until > now())
                """);
        List<Object> params = new ArrayList<>();
        params.add(toVectorString(queryEmbedding));
        params.add(databaseId);
        params.add(namespace);

        if (asOf != null) {
            sql.append(" AND created_at <= ? AND (expired_at IS NULL OR expired_at > ?)");
            params.add(Timestamp.from(asOf));
            params.add(Timestamp.from(asOf));
        }

        if (memoryTypeFilter != null) {
            sql.append(" AND memory_type = ?");
            params.add(memoryTypeFilter);
        }

        if (scopeFilter != null) {
            sql.append(" AND scope = ?");
            params.add(scopeFilter);
        }

        if (userIdFilter != null && !"agent".equals(scopeFilter)) {
            sql.append(" AND (user_id = ? OR scope = 'agent')");
            params.add(userIdFilter);
        }

        sql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");
        params.add(toVectorString(queryEmbedding));
        params.add(topK);

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof Integer intVal) ps.setInt(i + 1, intVal);
                else if (p instanceof Timestamp tsVal) ps.setTimestamp(i + 1, tsVal);
                else ps.setString(i + 1, String.valueOf(p));
            }
            ResultSet rs = ps.executeQuery();
            List<MemorySearchRow> results = new ArrayList<>();
            while (rs.next()) {
                results.add(new MemorySearchRow(searchRowFromResultSet(rs), rs.getDouble("similarity")));
            }
            return results;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to search memories", e);
        }
    }

    public List<MemorySearchRow> findConflicting(String databaseId, String namespace,
                                                    float[] embedding, double threshold,
                                                    String memoryType, String userId) {
        String sql = """
                SELECT id, user_id, namespace, memory_type, content, metadata,
                       importance, confidence, recency, source, access_count,
                       valid_from, valid_until, created_at, updated_at,
                       1 - (embedding <=> ?::vector) AS similarity
                FROM memories
                WHERE database_id = ?::uuid
                  AND namespace = ?
                  AND expired_at IS NULL
                  AND (valid_until IS NULL OR valid_until > now())
                  AND embedding IS NOT NULL
                  AND memory_type = ?
                  AND user_id = ?
                  AND 1 - (embedding <=> ?::vector) > ?
                ORDER BY embedding <=> ?::vector
                LIMIT 5
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            String vecStr = toVectorString(embedding);
            ps.setString(1, vecStr);
            ps.setString(2, databaseId);
            ps.setString(3, namespace);
            ps.setString(4, memoryType);
            ps.setString(5, userId);
            ps.setString(6, vecStr);
            ps.setDouble(7, threshold);
            ps.setString(8, vecStr);
            ResultSet rs = ps.executeQuery();
            List<MemorySearchRow> results = new ArrayList<>();
            while (rs.next()) {
                results.add(new MemorySearchRow(searchRowFromResultSet(rs), rs.getDouble("similarity")));
            }
            return results;
        } catch (SQLException e) {
            log.warn("Conflict search failed: {}", e.getMessage());
            return List.of();
        }
    }

    public List<MemorySearchRow> searchFullText(String databaseId, String namespace,
                                                  String query, int topK,
                                                  String memoryTypeFilter, String userIdFilter) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, user_id, namespace, memory_type, content, metadata,
                       importance, confidence, recency, source, access_count,
                       valid_from, valid_until, created_at, updated_at, scope,
                       ts_rank_cd(search_text, plainto_tsquery('english', ?)) AS similarity
                FROM memories
                WHERE database_id = ?::uuid
                  AND namespace = ?
                  AND expired_at IS NULL
                  AND (valid_until IS NULL OR valid_until > now())
                  AND search_text @@ plainto_tsquery('english', ?)
                """);
        List<Object> params = new ArrayList<>();
        params.add(query);
        params.add(databaseId);
        params.add(namespace);
        params.add(query);

        if (memoryTypeFilter != null) {
            sql.append(" AND memory_type = ?");
            params.add(memoryTypeFilter);
        }
        if (userIdFilter != null) {
            sql.append(" AND (user_id = ? OR scope = 'agent')");
            params.add(userIdFilter);
        }
        sql.append(" ORDER BY ts_rank_cd(search_text, plainto_tsquery('english', ?)) DESC LIMIT ?");
        params.add(query);
        params.add(topK);

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof Integer intVal) ps.setInt(i + 1, intVal);
                else ps.setString(i + 1, String.valueOf(p));
            }
            ResultSet rs = ps.executeQuery();
            List<MemorySearchRow> results = new ArrayList<>();
            while (rs.next()) {
                results.add(new MemorySearchRow(searchRowFromResultSet(rs), rs.getDouble("similarity")));
            }
            return results;
        } catch (SQLException e) {
            log.warn("Full-text search failed: {}", e.getMessage());
            return List.of();
        }
    }

    public void reinforceAccess(String databaseId, String id) {
        String sql = """
                UPDATE memories SET access_count = access_count + 1, updated_at = now()
                WHERE database_id = ?::uuid AND id = ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to reinforce memory access: {}", id, e);
        }
    }

    public boolean updateImportance(String databaseId, String id, double importance) {
        String sql = "UPDATE memories SET importance = ?, updated_at = now() WHERE database_id = ?::uuid AND id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, importance);
            ps.setString(2, databaseId);
            ps.setString(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update importance", e);
        }
    }

    public boolean updateMemory(String databaseId, String id, String memoryType, String content,
                                Map<String, Object> metadata, float[] embedding,
                                double importance) {
        String sql = """
                UPDATE memories
                SET memory_type = ?,
                    content = ?,
                    metadata = ?::jsonb,
                    embedding = COALESCE(?::vector, embedding),
                    importance = ?,
                    updated_at = now()
                WHERE database_id = ?::uuid
                  AND id = ?
                  AND expired_at IS NULL
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, memoryType);
            ps.setString(2, content);
            ps.setString(3, toJson(metadata));
            ps.setString(4, embedding != null ? toVectorString(embedding) : null);
            ps.setDouble(5, importance);
            ps.setString(6, databaseId);
            ps.setString(7, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update memory", e);
        }
    }

    public boolean expire(String databaseId, String id) {
        String sql = "UPDATE memories SET expired_at = now() WHERE database_id = ?::uuid AND id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to expire memory", e);
        }
    }

    public boolean delete(String databaseId, String userId, String id) {
        String sql = "DELETE FROM memories WHERE database_id = ?::uuid AND user_id = ? AND id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, userId);
            ps.setString(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete memory", e);
        }
    }

    public List<MemoryRow> getByUser(String databaseId, String userId, int limit) {
        String sql = """
                SELECT * FROM memories
                WHERE database_id = ?::uuid AND user_id = ?
                  AND expired_at IS NULL AND (valid_until IS NULL OR valid_until > now())
                ORDER BY importance DESC, updated_at DESC
                LIMIT ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, userId);
            ps.setInt(3, limit);
            ResultSet rs = ps.executeQuery();
            List<MemoryRow> rows = new ArrayList<>();
            while (rs.next()) rows.add(rowFromResultSet(rs));
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get memories by user", e);
        }
    }

    public List<MemoryRow> getByType(String databaseId, String memoryType, int limit) {
        String sql = """
                SELECT * FROM memories
                WHERE database_id = ?::uuid AND memory_type = ?
                  AND expired_at IS NULL AND (valid_until IS NULL OR valid_until > now())
                ORDER BY importance DESC
                LIMIT ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, memoryType);
            ps.setInt(3, limit);
            ResultSet rs = ps.executeQuery();
            List<MemoryRow> rows = new ArrayList<>();
            while (rs.next()) rows.add(rowFromResultSet(rs));
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get memories by type", e);
        }
    }

    public int applyDecay(String databaseId, String memoryType, double decayLambda) {
        String sql = """
                UPDATE memories
                SET recency = EXP(-? * EXTRACT(EPOCH FROM (now() - updated_at)) / 3600.0),
                    updated_at = now()
                WHERE database_id = ?::uuid AND memory_type = ? AND expired_at IS NULL
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, decayLambda);
            ps.setString(2, databaseId);
            ps.setString(3, memoryType);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to apply decay", e);
        }
    }

    public int countActive(String databaseId) {
        String sql = "SELECT COUNT(*) FROM memories WHERE database_id = ?::uuid AND expired_at IS NULL";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count memories", e);
        }
    }

    private MemoryRow rowFromResultSet(ResultSet rs) throws SQLException {
        return new MemoryRow(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("namespace"),
                rs.getString("memory_type"),
                rs.getString("content"),
                parseJson(rs.getString("metadata")),
                null,
                rs.getDouble("importance"),
                rs.getDouble("confidence"),
                rs.getDouble("recency"),
                rs.getString("source"),
                rs.getInt("access_count"),
                toInstant(rs.getTimestamp("valid_from")),
                toInstant(rs.getTimestamp("valid_until")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                getStringOrDefault(rs, "scope", "user")
        );
    }

    private MemoryRow searchRowFromResultSet(ResultSet rs) throws SQLException {
        return new MemoryRow(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("namespace"),
                rs.getString("memory_type"),
                rs.getString("content"),
                parseJson(rs.getString("metadata")),
                null,
                rs.getDouble("importance"),
                rs.getDouble("confidence"),
                rs.getDouble("recency"),
                rs.getString("source"),
                rs.getInt("access_count"),
                toInstant(rs.getTimestamp("valid_from")),
                toInstant(rs.getTimestamp("valid_until")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                getStringOrDefault(rs, "scope", "user")
        );
    }

    private String getStringOrDefault(ResultSet rs, String column, String defaultVal) {
        try {
            String val = rs.getString(column);
            return val != null ? val : defaultVal;
        } catch (SQLException e) {
            return defaultVal;
        }
    }

    private Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
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

    public record MemoryRow(
            String id, String userId, String namespace, String memoryType,
            String content, Map<String, Object> metadata, float[] embedding,
            double importance, double confidence, double recency, String source,
            int accessCount, Instant validFrom, Instant validUntil,
            Instant createdAt, Instant updatedAt, String scope
    ) {}

    public record MemorySearchRow(MemoryRow row, double similarity) {}
}
