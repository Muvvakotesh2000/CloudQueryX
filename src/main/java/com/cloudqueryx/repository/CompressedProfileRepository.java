package com.cloudqueryx.repository;

import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class CompressedProfileRepository {
    private final DatabaseConfig db;

    public CompressedProfileRepository(DatabaseConfig db) {
        this.db = db;
    }

    public Optional<ProfileRow> get(String databaseId, String subjectId) {
        ensureTable();
        String sql = """
                SELECT * FROM compressed_profiles
                WHERE database_id = ?::uuid AND subject_id = ? AND status = 'ACTIVE'
                ORDER BY version DESC
                LIMIT 1
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, subjectId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(row(rs));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load compressed profile", e);
        }
    }

    public ProfileRow upsert(String databaseId, String subjectId, String profileText, int tokenEstimate) {
        ensureTable();
        int nextVersion = get(databaseId, subjectId).map(p -> p.version() + 1).orElse(1);
        String sql = """
                INSERT INTO compressed_profiles
                    (id, database_id, subject_id, profile_text, token_estimate, version, status, updated_at)
                VALUES (?, ?::uuid, ?, ?, ?, ?, 'ACTIVE', now())
                ON CONFLICT (database_id, subject_id)
                DO UPDATE SET profile_text = EXCLUDED.profile_text,
                              token_estimate = EXCLUDED.token_estimate,
                              version = EXCLUDED.version,
                              status = 'ACTIVE',
                              updated_at = now()
                RETURNING *
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, databaseId);
            ps.setString(3, subjectId);
            ps.setString(4, profileText);
            ps.setInt(5, tokenEstimate);
            ps.setInt(6, nextVersion);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return row(rs);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert compressed profile", e);
        }
    }

    private ProfileRow row(ResultSet rs) throws SQLException {
        Timestamp updated = rs.getTimestamp("updated_at");
        return new ProfileRow(
                rs.getString("id"),
                rs.getString("database_id"),
                rs.getString("subject_id"),
                rs.getString("profile_text"),
                rs.getInt("token_estimate"),
                rs.getInt("version"),
                rs.getString("status"),
                updated == null ? null : updated.toInstant()
        );
    }

    private void ensureTable() {
        String sql = """
                CREATE TABLE IF NOT EXISTS compressed_profiles (
                    id TEXT PRIMARY KEY,
                    database_id UUID REFERENCES databases(id) ON DELETE CASCADE,
                    subject_id TEXT NOT NULL,
                    profile_text TEXT NOT NULL,
                    token_estimate INT DEFAULT 0,
                    version INT DEFAULT 1,
                    status TEXT DEFAULT 'ACTIVE',
                    created_at TIMESTAMPTZ DEFAULT now(),
                    updated_at TIMESTAMPTZ DEFAULT now(),
                    UNIQUE (database_id, subject_id)
                )
                """;
        try (Connection conn = db.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
            st.execute("CREATE INDEX IF NOT EXISTS idx_compressed_profiles_subject ON compressed_profiles (database_id, subject_id, status)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to ensure compressed profile table", e);
        }
    }

    public record ProfileRow(
            String id,
            String databaseId,
            String subjectId,
            String profileText,
            int tokenEstimate,
            int version,
            String status,
            Instant updatedAt
    ) {}
}
