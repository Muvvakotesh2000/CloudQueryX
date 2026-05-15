package com.cloudqueryx.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public class VectorRepository {

    private static final Logger log = LoggerFactory.getLogger(VectorRepository.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final DatabaseConfig db;

    public VectorRepository(DatabaseConfig db) {
        this.db = db;
    }

    public void upsert(String databaseId, String namespace, String id,
                        float[] embedding, String content, Map<String, Object> metadata) {
        String sql = """
                INSERT INTO vectors (id, database_id, namespace, embedding, content, metadata)
                VALUES (?, ?::uuid, ?, ?::vector, ?, ?::jsonb)
                ON CONFLICT (database_id, namespace, id)
                DO UPDATE SET embedding = EXCLUDED.embedding,
                              content = EXCLUDED.content,
                              metadata = EXCLUDED.metadata,
                              updated_at = now()
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, databaseId);
            ps.setString(3, namespace);
            ps.setString(4, toVectorString(embedding));
            ps.setString(5, content);
            ps.setString(6, toJson(metadata));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert vector", e);
        }
    }

    public List<VectorRow> search(String databaseId, String namespace,
                                   float[] queryEmbedding, int topK) {
        String sql = """
                SELECT id, namespace, content, metadata,
                       1 - (embedding <=> ?::vector) AS score
                FROM vectors
                WHERE database_id = ?::uuid AND namespace = ?
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            String vecStr = toVectorString(queryEmbedding);
            ps.setString(1, vecStr);
            ps.setString(2, databaseId);
            ps.setString(3, namespace);
            ps.setString(4, vecStr);
            ps.setInt(5, topK);
            return readVectorRows(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to search vectors", e);
        }
    }

    public List<VectorRow> hybridSearch(String databaseId, String namespace,
                                         float[] queryEmbedding, String keyword, int topK) {
        String sql = """
                SELECT id, namespace, content, metadata,
                       1 - (embedding <=> ?::vector) AS score
                FROM vectors
                WHERE database_id = ?::uuid AND namespace = ?
                  AND content ILIKE ?
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            String vecStr = toVectorString(queryEmbedding);
            ps.setString(1, vecStr);
            ps.setString(2, databaseId);
            ps.setString(3, namespace);
            ps.setString(4, "%" + keyword + "%");
            ps.setString(5, vecStr);
            ps.setInt(6, topK);
            return readVectorRows(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to hybrid search vectors", e);
        }
    }

    public boolean delete(String databaseId, String namespace, String id) {
        String sql = "DELETE FROM vectors WHERE database_id = ?::uuid AND namespace = ? AND id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, namespace);
            ps.setString(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete vector", e);
        }
    }

    public int countByNamespace(String databaseId, String namespace) {
        String sql = "SELECT COUNT(*) FROM vectors WHERE database_id = ?::uuid AND namespace = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, namespace);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count vectors", e);
        }
    }

    private List<VectorRow> readVectorRows(ResultSet rs) throws SQLException {
        List<VectorRow> rows = new ArrayList<>();
        while (rs.next()) {
            rows.add(new VectorRow(
                    rs.getString("id"),
                    rs.getString("namespace"),
                    rs.getString("content"),
                    parseJson(rs.getString("metadata")),
                    rs.getDouble("score")
            ));
        }
        return rows;
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

    public record VectorRow(String id, String namespace, String content,
                             Map<String, Object> metadata, double score) {}
}
