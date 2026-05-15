package com.cloudqueryx.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

public class DatabaseApiKeyRepository {
    private static final SecureRandom random = new SecureRandom();
    private final DatabaseConfig db;

    public DatabaseApiKeyRepository(DatabaseConfig db) {
        this.db = db;
    }

    public CreatedApiKey create(String databaseId, String ownerUserId, String name) {
        String rawKey = generateRawKey();
        String prefix = rawKey.substring(0, Math.min(rawKey.length(), 18));
        String sql = """
                INSERT INTO database_api_keys (database_id, owner_user_id, key_hash, key_prefix, name)
                VALUES (?::uuid, ?::uuid, ?, ?, ?)
                RETURNING id, created_at
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, ownerUserId);
            ps.setString(3, hash(rawKey));
            ps.setString(4, prefix);
            ps.setString(5, name == null || name.isBlank() ? "Default API key" : name);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) throw new IllegalStateException("INSERT RETURNING produced no rows");
            ApiKeyRow row = new ApiKeyRow(
                    rs.getString("id"), databaseId, ownerUserId, prefix,
                    name == null || name.isBlank() ? "Default API key" : name,
                    "ACTIVE", null, rs.getTimestamp("created_at").toInstant(), null
            );
            return new CreatedApiKey(row, rawKey);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create database API key", e);
        }
    }

    public Optional<ApiKeyAuth> authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return Optional.empty();
        String sql = """
                SELECT k.*, d.status AS database_status
                FROM database_api_keys k
                JOIN databases d ON d.id = k.database_id
                WHERE k.key_hash = ?
                  AND k.status = 'ACTIVE'
                  AND k.revoked_at IS NULL
                  AND d.status <> 'DELETED'
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash(rawKey));
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            ApiKeyAuth auth = new ApiKeyAuth(
                    rs.getString("id"),
                    rs.getString("database_id"),
                    rs.getString("owner_user_id"),
                    rs.getString("key_prefix"),
                    rs.getString("name")
            );
            touch(conn, auth.id());
            return Optional.of(auth);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to authenticate database API key", e);
        }
    }

    public List<ApiKeyRow> list(String databaseId, String ownerUserId) {
        String sql = """
                SELECT id, database_id, owner_user_id, key_prefix, name, status,
                       last_used_at, created_at, revoked_at
                FROM database_api_keys
                WHERE database_id = ?::uuid AND owner_user_id = ?::uuid
                ORDER BY created_at DESC
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, ownerUserId);
            ResultSet rs = ps.executeQuery();
            List<ApiKeyRow> rows = new ArrayList<>();
            while (rs.next()) rows.add(row(rs));
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list database API keys", e);
        }
    }

    public boolean revoke(String databaseId, String ownerUserId, String keyId) {
        String sql = """
                UPDATE database_api_keys
                SET status = 'REVOKED', revoked_at = now()
                WHERE id = ?::uuid AND database_id = ?::uuid AND owner_user_id = ?::uuid
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, keyId);
            ps.setString(2, databaseId);
            ps.setString(3, ownerUserId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to revoke database API key", e);
        }
    }

    private void touch(Connection conn, String keyId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE database_api_keys SET last_used_at = now() WHERE id = ?::uuid")) {
            ps.setString(1, keyId);
            ps.executeUpdate();
        }
    }

    private ApiKeyRow row(ResultSet rs) throws SQLException {
        Timestamp lastUsed = rs.getTimestamp("last_used_at");
        Timestamp revoked = rs.getTimestamp("revoked_at");
        return new ApiKeyRow(
                rs.getString("id"),
                rs.getString("database_id"),
                rs.getString("owner_user_id"),
                rs.getString("key_prefix"),
                rs.getString("name"),
                rs.getString("status"),
                lastUsed != null ? lastUsed.toInstant() : null,
                rs.getTimestamp("created_at").toInstant(),
                revoked != null ? revoked.toInstant() : null
        );
    }

    private String generateRawKey() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "cqx_live_" + HexFormat.of().formatHex(bytes);
    }

    public static String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record CreatedApiKey(ApiKeyRow key, String rawKey) {}

    public record ApiKeyAuth(String id, String databaseId, String ownerUserId, String keyPrefix, String name) {}

    public record ApiKeyRow(
            String id,
            String databaseId,
            String ownerUserId,
            String keyPrefix,
            String name,
            String status,
            Instant lastUsedAt,
            Instant createdAt,
            Instant revokedAt
    ) {}
}
