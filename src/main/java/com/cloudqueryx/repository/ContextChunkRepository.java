package com.cloudqueryx.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public class ContextChunkRepository {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final DatabaseConfig db;

    public ContextChunkRepository(DatabaseConfig db) {
        this.db = db;
    }

    public void replaceChunks(String databaseId, String sourceId, List<ChunkRow> chunks) {
        String deleteSql = "DELETE FROM context_chunks WHERE database_id = ?::uuid AND source_id = ?";
        String insertSql = """
                INSERT INTO context_chunks (id, source_id, database_id, chunk_index,
                    content, token_estimate, content_hash, metadata)
                VALUES (?, ?, ?::uuid, ?, ?, ?, ?, ?::jsonb)
                """;
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement delete = conn.prepareStatement(deleteSql)) {
                delete.setString(1, databaseId);
                delete.setString(2, sourceId);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = conn.prepareStatement(insertSql)) {
                for (ChunkRow chunk : chunks) {
                    insert.setString(1, chunk.id());
                    insert.setString(2, chunk.sourceId());
                    insert.setString(3, chunk.databaseId());
                    insert.setInt(4, chunk.chunkIndex());
                    insert.setString(5, chunk.content());
                    insert.setInt(6, chunk.tokenEstimate());
                    insert.setString(7, chunk.contentHash());
                    insert.setString(8, toJson(chunk.metadata()));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to replace chunks", e);
        }
    }

    public void upsertEmbedding(String databaseId, String chunkId, float[] embedding, String model) {
        if (embedding == null) return;
        String sql = """
                INSERT INTO context_embeddings (id, database_id, chunk_id, embedding, embedding_model)
                VALUES (?, ?::uuid, ?, ?::vector, ?)
                ON CONFLICT (database_id, id)
                DO UPDATE SET embedding = EXCLUDED.embedding,
                              embedding_model = EXCLUDED.embedding_model,
                              created_at = now()
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, chunkId);
            ps.setString(2, databaseId);
            ps.setString(3, chunkId);
            ps.setString(4, toVectorString(embedding));
            ps.setString(5, model);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert chunk embedding", e);
        }
    }

    public List<ChunkSearchRow> search(String databaseId, float[] queryEmbedding, String query,
                                       int topK, List<String> sourceTypes) {
        List<ChunkSearchRow> results = new ArrayList<>();
        Map<String, ChunkSearchRow> merged = new LinkedHashMap<>();

        if (queryEmbedding != null) {
            for (ChunkSearchRow row : searchVector(databaseId, queryEmbedding, topK * 2, sourceTypes)) {
                merged.put(row.chunk().id(), row);
            }
        }
        if (query != null && !query.isBlank()) {
            for (ChunkSearchRow row : searchText(databaseId, query, topK * 2, sourceTypes)) {
                ChunkSearchRow existing = merged.get(row.chunk().id());
                if (existing == null) {
                    merged.put(row.chunk().id(), row);
                } else {
                    merged.put(row.chunk().id(), new ChunkSearchRow(
                            existing.chunk(),
                            Math.max(existing.vectorScore(), row.vectorScore()),
                            Math.max(existing.textScore(), row.textScore())
                    ));
                }
            }
        }

        results.addAll(merged.values());
        results.sort(Comparator.<ChunkSearchRow>comparingDouble(
                r -> Math.max(r.vectorScore(), 0) + Math.max(r.textScore(), 0)
        ).reversed());
        return results.stream().limit(topK).toList();
    }

    private List<ChunkSearchRow> searchVector(String databaseId, float[] queryEmbedding, int topK, List<String> sourceTypes) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.*, s.source_type, s.source_name, s.updated_at AS source_updated_at,
                       1 - (e.embedding <=> ?::vector) AS vector_score,
                       0.0 AS text_score
                FROM context_embeddings e
                JOIN context_chunks c ON c.database_id = e.database_id AND c.id = e.chunk_id
                JOIN sources s ON s.database_id = c.database_id AND s.id = c.source_id
                WHERE c.database_id = ?::uuid AND s.status = 'ACTIVE'
                """);
        List<Object> params = new ArrayList<>();
        String vec = toVectorString(queryEmbedding);
        params.add(vec);
        params.add(databaseId);
        appendSourceTypeFilter(sql, params, sourceTypes);
        sql.append(" ORDER BY e.embedding <=> ?::vector LIMIT ?");
        params.add(vec);
        params.add(topK);
        return queryRows(sql.toString(), params);
    }

    private List<ChunkSearchRow> searchText(String databaseId, String query, int topK, List<String> sourceTypes) {
        StringBuilder sql = new StringBuilder("""
                WITH ranked AS (
                    SELECT c.database_id, c.id,
                           ts_rank_cd(c.search_text, plainto_tsquery('english', ?)) AS text_score
                    FROM context_chunks c
                    JOIN sources s ON s.database_id = c.database_id AND s.id = c.source_id
                    WHERE c.database_id = ?::uuid AND s.status = 'ACTIVE'
                      AND c.search_text @@ plainto_tsquery('english', ?)
                """);
        List<Object> params = new ArrayList<>();
        params.add(query);
        params.add(databaseId);
        params.add(query);
        appendSourceTypeFilter(sql, params, sourceTypes);
        sql.append("""
                    ORDER BY text_score DESC
                    LIMIT ?
                )
                SELECT c.*, s.source_type, s.source_name, s.updated_at AS source_updated_at,
                       0.0 AS vector_score,
                       ranked.text_score AS text_score
                FROM context_chunks c
                JOIN ranked ON ranked.database_id = c.database_id AND ranked.id = c.id
                JOIN sources s ON s.database_id = c.database_id AND s.id = c.source_id
                ORDER BY ranked.text_score DESC
                """);
        params.add(topK);
        return queryRows(sql.toString(), params);
    }

    private void appendSourceTypeFilter(StringBuilder sql, List<Object> params, List<String> sourceTypes) {
        if (sourceTypes == null || sourceTypes.isEmpty()) return;
        sql.append(" AND s.source_type = ANY (?::text[])");
        params.add(sourceTypes.toArray(new String[0]));
    }

    private List<ChunkSearchRow> queryRows(String sql, List<Object> params) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof Integer intVal) ps.setInt(i + 1, intVal);
                else if (p instanceof String[] arr) ps.setArray(i + 1, conn.createArrayOf("text", arr));
                else ps.setString(i + 1, String.valueOf(p));
            }
            ResultSet rs = ps.executeQuery();
            List<ChunkSearchRow> rows = new ArrayList<>();
            while (rs.next()) rows.add(searchRow(rs));
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to search context chunks", e);
        }
    }

    private ChunkSearchRow searchRow(ResultSet rs) throws SQLException {
        ChunkRow chunk = new ChunkRow(
                rs.getString("id"),
                rs.getString("database_id"),
                rs.getString("source_id"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                rs.getInt("token_estimate"),
                rs.getString("content_hash"),
                parseJson(rs.getString("metadata")),
                rs.getString("source_type"),
                rs.getString("source_name"),
                toInstant(rs.getTimestamp("source_updated_at")),
                toInstant(rs.getTimestamp("created_at"))
        );
        return new ChunkSearchRow(chunk, rs.getDouble("vector_score"), rs.getDouble("text_score"));
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

    public record ChunkRow(
            String id,
            String databaseId,
            String sourceId,
            int chunkIndex,
            String content,
            int tokenEstimate,
            String contentHash,
            Map<String, Object> metadata,
            String sourceType,
            String sourceName,
            Instant sourceUpdatedAt,
            Instant createdAt
    ) {}

    public record ChunkSearchRow(ChunkRow chunk, double vectorScore, double textScore) {}
}
