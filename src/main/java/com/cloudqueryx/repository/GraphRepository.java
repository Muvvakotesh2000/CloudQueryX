package com.cloudqueryx.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public class GraphRepository {

    private static final Logger log = LoggerFactory.getLogger(GraphRepository.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final DatabaseConfig db;

    public GraphRepository(DatabaseConfig db) {
        this.db = db;
    }

    // ─── Entities ──────────────────────────────────────────────────────

    public void upsertEntity(String databaseId, EntityRow entity) {
        String sql = """
                INSERT INTO entities (id, database_id, user_id, entity_type, name, description,
                    attributes, embedding, confidence, source)
                VALUES (?, ?::uuid, ?, ?, ?, ?, ?::jsonb, ?::vector, ?, ?)
                ON CONFLICT (database_id, id)
                DO UPDATE SET name = EXCLUDED.name,
                              description = EXCLUDED.description,
                              attributes = EXCLUDED.attributes,
                              embedding = EXCLUDED.embedding,
                              confidence = EXCLUDED.confidence,
                              updated_at = now()
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entity.id);
            ps.setString(2, databaseId);
            ps.setString(3, entity.userId);
            ps.setString(4, entity.entityType);
            ps.setString(5, entity.name);
            ps.setString(6, entity.description);
            ps.setString(7, toJson(entity.attributes));
            ps.setString(8, entity.embedding != null ? toVectorString(entity.embedding) : null);
            ps.setDouble(9, entity.confidence);
            ps.setString(10, entity.source);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert entity", e);
        }
    }

    public Optional<EntityRow> getEntity(String databaseId, String id) {
        String sql = "SELECT * FROM entities WHERE database_id = ?::uuid AND id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(entityFromResultSet(rs));
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get entity", e);
        }
    }

    public List<EntityRow> findEntitiesByType(String databaseId, String entityType) {
        String sql = "SELECT * FROM entities WHERE database_id = ?::uuid AND entity_type = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, entityType);
            ResultSet rs = ps.executeQuery();
            List<EntityRow> rows = new ArrayList<>();
            while (rs.next()) rows.add(entityFromResultSet(rs));
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find entities by type", e);
        }
    }

    public List<EntityRow> findEntitiesByUser(String databaseId, String userId) {
        String sql = "SELECT * FROM entities WHERE database_id = ?::uuid AND user_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, userId);
            ResultSet rs = ps.executeQuery();
            List<EntityRow> rows = new ArrayList<>();
            while (rs.next()) rows.add(entityFromResultSet(rs));
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find entities by user", e);
        }
    }

    public List<EntityRow> listEntities(String databaseId, int limit) {
        String sql = "SELECT * FROM entities WHERE database_id = ?::uuid ORDER BY updated_at DESC LIMIT ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            List<EntityRow> rows = new ArrayList<>();
            while (rs.next()) rows.add(entityFromResultSet(rs));
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list entities", e);
        }
    }

    public List<EntitySearchRow> searchEntities(String databaseId, float[] queryEmbedding, int topK) {
        String sql = """
                SELECT *, 1 - (embedding <=> ?::vector) AS similarity
                FROM entities
                WHERE database_id = ?::uuid AND embedding IS NOT NULL
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            String vecStr = toVectorString(queryEmbedding);
            ps.setString(1, vecStr);
            ps.setString(2, databaseId);
            ps.setString(3, vecStr);
            ps.setInt(4, topK);
            ResultSet rs = ps.executeQuery();
            List<EntitySearchRow> rows = new ArrayList<>();
            while (rs.next()) rows.add(new EntitySearchRow(entityFromResultSet(rs), rs.getDouble("similarity")));
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to search entities", e);
        }
    }

    public boolean deleteEntity(String databaseId, String id) {
        String sql = "DELETE FROM entities WHERE database_id = ?::uuid AND id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete entity", e);
        }
    }

    // ─── Relationships ─────────────────────────────────────────────────

    public void upsertRelationship(String databaseId, RelationshipRow rel) {
        String sql = """
                INSERT INTO relationships (id, database_id, source_entity_id, target_entity_id,
                    relationship_type, attributes, weight, confidence, source, valid_from, valid_until)
                VALUES (?, ?::uuid, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                ON CONFLICT (database_id, id)
                DO UPDATE SET attributes = EXCLUDED.attributes,
                              weight = EXCLUDED.weight,
                              confidence = EXCLUDED.confidence
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, rel.id);
            ps.setString(2, databaseId);
            ps.setString(3, rel.sourceEntityId);
            ps.setString(4, rel.targetEntityId);
            ps.setString(5, rel.relationshipType);
            ps.setString(6, toJson(rel.attributes));
            ps.setDouble(7, rel.weight);
            ps.setDouble(8, rel.confidence);
            ps.setString(9, rel.source);
            ps.setTimestamp(10, rel.validFrom != null ? Timestamp.from(rel.validFrom) : null);
            ps.setTimestamp(11, rel.validUntil != null ? Timestamp.from(rel.validUntil) : null);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert relationship", e);
        }
    }

    public List<RelationshipRow> getRelationshipsFrom(String databaseId, String entityId) {
        String sql = """
                SELECT * FROM relationships
                WHERE database_id = ?::uuid AND source_entity_id = ?
                  AND (valid_until IS NULL OR valid_until > now())
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, entityId);
            return readRelationships(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get outgoing relationships", e);
        }
    }

    public List<RelationshipRow> getRelationshipsTo(String databaseId, String entityId) {
        String sql = """
                SELECT * FROM relationships
                WHERE database_id = ?::uuid AND target_entity_id = ?
                  AND (valid_until IS NULL OR valid_until > now())
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, entityId);
            return readRelationships(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get incoming relationships", e);
        }
    }

    public List<RelationshipRow> listRelationships(String databaseId, int limit) {
        String sql = """
                SELECT * FROM relationships
                WHERE database_id = ?::uuid AND (valid_until IS NULL OR valid_until > now())
                ORDER BY created_at DESC
                LIMIT ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setInt(2, limit);
            return readRelationships(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list relationships", e);
        }
    }

    public List<EntityRow> findRelated(String databaseId, String entityId,
                                        String relationshipType, int maxDepth) {
        // Use recursive CTE for graph traversal
        String sql = """
                WITH RECURSIVE traversal AS (
                    SELECT target_entity_id AS entity_id, 1 AS depth
                    FROM relationships
                    WHERE database_id = ?::uuid AND source_entity_id = ?
                      AND (valid_until IS NULL OR valid_until > now())
                      AND (?::text IS NULL OR relationship_type = ?::text)
                    UNION
                    SELECT r.target_entity_id, t.depth + 1
                    FROM relationships r
                    JOIN traversal t ON r.source_entity_id = t.entity_id
                    WHERE r.database_id = ?::uuid AND t.depth < ?
                      AND (r.valid_until IS NULL OR r.valid_until > now())
                      AND (?::text IS NULL OR r.relationship_type = ?::text)
                )
                SELECT DISTINCT e.* FROM traversal t
                JOIN entities e ON e.database_id = ?::uuid AND e.id = t.entity_id
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, entityId);
            ps.setString(3, relationshipType);
            ps.setString(4, relationshipType);
            ps.setString(5, databaseId);
            ps.setInt(6, maxDepth);
            ps.setString(7, relationshipType);
            ps.setString(8, relationshipType);
            ps.setString(9, databaseId);
            ResultSet rs = ps.executeQuery();
            List<EntityRow> rows = new ArrayList<>();
            while (rs.next()) rows.add(entityFromResultSet(rs));
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find related entities", e);
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private EntityRow entityFromResultSet(ResultSet rs) throws SQLException {
        return new EntityRow(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("entity_type"),
                rs.getString("name"),
                rs.getString("description"),
                parseJson(rs.getString("attributes")),
                null, // embedding not loaded in row queries
                rs.getDouble("confidence"),
                rs.getString("source"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private List<RelationshipRow> readRelationships(ResultSet rs) throws SQLException {
        List<RelationshipRow> rows = new ArrayList<>();
        while (rs.next()) {
            rows.add(new RelationshipRow(
                    rs.getString("id"),
                    rs.getString("source_entity_id"),
                    rs.getString("target_entity_id"),
                    rs.getString("relationship_type"),
                    parseJson(rs.getString("attributes")),
                    rs.getDouble("weight"),
                    rs.getDouble("confidence"),
                    rs.getString("source"),
                    toInstant(rs.getTimestamp("valid_from")),
                    toInstant(rs.getTimestamp("valid_until")),
                    toInstant(rs.getTimestamp("created_at"))
            ));
        }
        return rows;
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

    public record EntityRow(
            String id, String userId, String entityType, String name, String description,
            Map<String, Object> attributes, float[] embedding, double confidence,
            String source, Instant createdAt, Instant updatedAt
    ) {}

    public record EntitySearchRow(EntityRow entity, double similarity) {}

    public record RelationshipRow(
            String id, String sourceEntityId, String targetEntityId, String relationshipType,
            Map<String, Object> attributes, double weight, double confidence, String source,
            Instant validFrom, Instant validUntil, Instant createdAt
    ) {}
}
