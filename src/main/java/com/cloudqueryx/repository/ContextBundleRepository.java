package com.cloudqueryx.repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public class ContextBundleRepository {

    private final DatabaseConfig db;

    public ContextBundleRepository(DatabaseConfig db) {
        this.db = db;
    }

    public void save(BundleRow bundle, List<BundleItemRow> items) {
        String bundleSql = """
                INSERT INTO context_bundles (id, database_id, user_id, query, target_model,
                    mode, token_budget, estimated_tokens, freshness_status, formatted_context)
                VALUES (?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        String itemSql = """
                INSERT INTO context_bundle_items (id, database_id, bundle_id, item_type,
                    source_id, chunk_id, memory_id, content, token_estimate, score, reason,
                    retrieval_source, compression_decision, freshness_decision)
                VALUES (?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(bundleSql)) {
                ps.setString(1, bundle.id());
                ps.setString(2, bundle.databaseId());
                ps.setString(3, bundle.userId());
                ps.setString(4, bundle.query());
                ps.setString(5, bundle.targetModel());
                ps.setString(6, bundle.mode());
                ps.setInt(7, bundle.tokenBudget());
                ps.setInt(8, bundle.estimatedTokens());
                ps.setString(9, bundle.freshnessStatus());
                ps.setString(10, bundle.formattedContext());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(itemSql)) {
                for (BundleItemRow item : items) {
                    ps.setString(1, item.id());
                    ps.setString(2, item.databaseId());
                    ps.setString(3, item.bundleId());
                    ps.setString(4, item.itemType());
                    ps.setString(5, item.sourceId());
                    ps.setString(6, item.chunkId());
                    ps.setString(7, item.memoryId());
                    ps.setString(8, item.content());
                    ps.setInt(9, item.tokenEstimate());
                    ps.setDouble(10, item.score());
                    ps.setString(11, item.reason());
                    ps.setString(12, item.retrievalSource());
                    ps.setString(13, item.compressionDecision());
                    ps.setString(14, item.freshnessDecision());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save context bundle", e);
        }
    }

    public Optional<BundleWithItems> get(String databaseId, String bundleId) {
        String bundleSql = "SELECT * FROM context_bundles WHERE database_id = ?::uuid AND id = ?";
        String itemSql = """
                SELECT * FROM context_bundle_items
                WHERE database_id = ?::uuid AND bundle_id = ?
                ORDER BY score DESC, created_at ASC
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement bundlePs = conn.prepareStatement(bundleSql)) {
            bundlePs.setString(1, databaseId);
            bundlePs.setString(2, bundleId);
            ResultSet brs = bundlePs.executeQuery();
            if (!brs.next()) return Optional.empty();
            BundleRow bundle = bundleRow(brs);

            List<BundleItemRow> items = new ArrayList<>();
            try (PreparedStatement itemPs = conn.prepareStatement(itemSql)) {
                itemPs.setString(1, databaseId);
                itemPs.setString(2, bundleId);
                ResultSet irs = itemPs.executeQuery();
                while (irs.next()) items.add(itemRow(irs));
            }
            return Optional.of(new BundleWithItems(bundle, items));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get context bundle", e);
        }
    }

    private BundleRow bundleRow(ResultSet rs) throws SQLException {
        return new BundleRow(
                rs.getString("id"),
                rs.getString("database_id"),
                rs.getString("user_id"),
                rs.getString("query"),
                rs.getString("target_model"),
                rs.getString("mode"),
                rs.getInt("token_budget"),
                rs.getInt("estimated_tokens"),
                rs.getString("freshness_status"),
                rs.getString("formatted_context"),
                toInstant(rs.getTimestamp("created_at"))
        );
    }

    private BundleItemRow itemRow(ResultSet rs) throws SQLException {
        return new BundleItemRow(
                rs.getString("id"),
                rs.getString("database_id"),
                rs.getString("bundle_id"),
                rs.getString("item_type"),
                rs.getString("source_id"),
                rs.getString("chunk_id"),
                rs.getString("memory_id"),
                rs.getString("content"),
                rs.getInt("token_estimate"),
                rs.getDouble("score"),
                rs.getString("reason"),
                rs.getString("retrieval_source"),
                rs.getString("compression_decision"),
                rs.getString("freshness_decision"),
                toInstant(rs.getTimestamp("created_at"))
        );
    }

    private Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    public record BundleRow(
            String id,
            String databaseId,
            String userId,
            String query,
            String targetModel,
            String mode,
            int tokenBudget,
            int estimatedTokens,
            String freshnessStatus,
            String formattedContext,
            Instant createdAt
    ) {}

    public record BundleItemRow(
            String id,
            String databaseId,
            String bundleId,
            String itemType,
            String sourceId,
            String chunkId,
            String memoryId,
            String content,
            int tokenEstimate,
            double score,
            String reason,
            String retrievalSource,
            String compressionDecision,
            String freshnessDecision,
            Instant createdAt
    ) {}

    public record BundleWithItems(BundleRow bundle, List<BundleItemRow> items) {}
}
