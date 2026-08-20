package com.cloudqueryx.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DatabaseRepository {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRepository.class);
    private final DatabaseConfig db;

    public DatabaseRepository(DatabaseConfig db) {
        this.db = db;
    }

    public DatabaseRow create(String userId, String name) {
        return create(userId, name, "");
    }

    public DatabaseRow ensureDefaultForUser(String userId) {
        List<DatabaseRow> existing = listForUser(userId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return create(userId, "Personal Context Memory",
                "Automatically created private CloudQueryX context database.");
    }

    public DatabaseRow create(String userId, String name, String description) {
        String sql = """
                INSERT INTO databases (user_id, name, description)
                VALUES (?::uuid, ?, ?)
                RETURNING id, user_id, name, description, status, created_at, updated_at
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, name);
            ps.setString(3, description != null ? description : "");
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rowFromResultSet(rs);
            }
            throw new IllegalStateException("INSERT RETURNING produced no rows");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create database", e);
        }
    }

    public Optional<DatabaseRow> get(String dbId, String userId) {
        String sql = """
                SELECT id, user_id, name, description, status, created_at, updated_at
                FROM databases
                WHERE id = ?::uuid AND user_id = ?::uuid AND status <> 'DELETED'
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dbId);
            ps.setString(2, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(rowFromResultSet(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch database", e);
        }
    }

    public List<DatabaseRow> listForUser(String userId) {
        String sql = """
                SELECT id, user_id, name, description, status, created_at, updated_at
                FROM databases
                WHERE user_id = ?::uuid AND status <> 'DELETED'
                ORDER BY created_at DESC
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ResultSet rs = ps.executeQuery();
            List<DatabaseRow> list = new ArrayList<>();
            while (rs.next()) {
                list.add(rowFromResultSet(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list databases", e);
        }
    }

    public boolean delete(String dbId, String userId) {
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!ownsDatabase(conn, dbId, userId)) {
                    conn.rollback();
                    return false;
                }

                deleteWhereDatabase(conn, "context_bundle_items", dbId);
                deleteWhereDatabase(conn, "context_bundles", dbId);
                deleteWhereDatabase(conn, "project_file_versions", dbId);
                deleteWhereDatabase(conn, "project_files", dbId);
                deleteWhereDatabase(conn, "coding_projects", dbId);
                deleteWhereDatabase(conn, "context_embeddings", dbId);
                deleteWhereDatabase(conn, "context_chunks", dbId);
                deleteWhereDatabase(conn, "sources", dbId);
                deleteWhereDatabase(conn, "memory_embeddings", dbId);
                deleteWhereDatabase(conn, "memories", dbId);
                deleteWhereDatabase(conn, "relationships", dbId);
                deleteWhereDatabase(conn, "entities", dbId);
                deleteWhereDatabase(conn, "events", dbId);
                deleteWhereDatabase(conn, "vectors", dbId);
                deleteWhereDatabase(conn, "webhooks", dbId);
                deleteWhereDatabase(conn, "database_api_keys", dbId);
                deleteWhereDatabase(conn, "user_table_rows",
                        "table_id IN (SELECT id FROM user_tables WHERE database_id = ?::uuid)", dbId);
                deleteWhereDatabase(conn, "user_tables", dbId);

                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM databases WHERE id = ?::uuid AND user_id = ?::uuid")) {
                    ps.setString(1, dbId);
                    ps.setString(2, userId);
                    boolean deleted = ps.executeUpdate() > 0;
                    conn.commit();
                    return deleted;
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete database", e);
        }
    }

    private boolean ownsDatabase(Connection conn, String dbId, String userId) throws SQLException {
        String sql = "SELECT 1 FROM databases WHERE id = ?::uuid AND user_id = ?::uuid";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dbId);
            ps.setString(2, userId);
            return ps.executeQuery().next();
        }
    }

    private void deleteWhereDatabase(Connection conn, String table, String dbId) throws SQLException {
        deleteWhereDatabase(conn, table, "database_id = ?::uuid", dbId);
    }

    private void deleteWhereDatabase(Connection conn, String table, String predicate, String dbId) throws SQLException {
        if (!tableExists(conn, table)) return;
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE " + predicate)) {
            ps.setString(1, dbId);
            ps.executeUpdate();
        }
    }

    private boolean tableExists(Connection conn, String table) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(null, "public", table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private DatabaseRow rowFromResultSet(ResultSet rs) throws SQLException {
        return new DatabaseRow(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("name"),
                getStringOrDefault(rs, "description", ""),
                getStringOrDefault(rs, "status", "ACTIVE"),
                rs.getTimestamp("created_at").toInstant(),
                timestampOrCreated(rs)
        );
    }

    private String getStringOrDefault(ResultSet rs, String column, String defaultValue) {
        try {
            String value = rs.getString(column);
            return value != null ? value : defaultValue;
        } catch (SQLException ignored) {
            return defaultValue;
        }
    }

    private Instant timestampOrCreated(ResultSet rs) throws SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return updatedAt != null ? updatedAt.toInstant() : rs.getTimestamp("created_at").toInstant();
    }

    public record DatabaseRow(
            String id,
            String userId,
            String name,
            String description,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
