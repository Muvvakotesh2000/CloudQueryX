package com.cloudqueryx.repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public class AgenticMemoryFileRepository {
    private final DatabaseConfig db;

    public AgenticMemoryFileRepository(DatabaseConfig db) {
        this.db = db;
    }

    public FileRow write(String databaseId, String userId, String path, String content, String summary) {
        ensureTable();
        String safePath = safePath(path);
        String sql = """
                INSERT INTO agentic_memory_files
                    (id, database_id, user_id, path, content, summary, version, updated_at)
                VALUES (?, ?::uuid, ?, ?, ?, ?, 1, now())
                ON CONFLICT (database_id, path)
                DO UPDATE SET content = EXCLUDED.content,
                              summary = EXCLUDED.summary,
                              version = agentic_memory_files.version + 1,
                              updated_at = now()
                RETURNING *
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, databaseId);
            ps.setString(3, userId);
            ps.setString(4, safePath);
            ps.setString(5, content);
            ps.setString(6, summary == null || summary.isBlank() ? firstLine(content) : summary);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return row(rs);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to write agentic memory file", e);
        }
    }

    public Optional<FileRow> view(String databaseId, String path) {
        ensureTable();
        String sql = "SELECT * FROM agentic_memory_files WHERE database_id = ?::uuid AND path = ? AND status = 'ACTIVE'";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, safePath(path));
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(row(rs));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to view agentic memory file", e);
        }
    }

    public List<FileRow> search(String databaseId, String query, int limit) {
        ensureTable();
        String sql = """
                SELECT *, ts_rank_cd(search_text, plainto_tsquery('english', ?)) AS rank
                FROM agentic_memory_files
                WHERE database_id = ?::uuid
                  AND status = 'ACTIVE'
                  AND (
                    search_text @@ plainto_tsquery('english', ?)
                    OR lower(path) LIKE lower(?)
                    OR lower(summary) LIKE lower(?)
                  )
                ORDER BY rank DESC, updated_at DESC
                LIMIT ?
                """;
        String like = "%" + (query == null ? "" : query) + "%";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, query);
            ps.setString(2, databaseId);
            ps.setString(3, query);
            ps.setString(4, like);
            ps.setString(5, like);
            ps.setInt(6, limit);
            ResultSet rs = ps.executeQuery();
            List<FileRow> rows = new ArrayList<>();
            while (rs.next()) rows.add(row(rs));
            return rows;
        } catch (SQLException e) {
            return listRecent(databaseId, limit);
        }
    }

    public List<FileRow> listRecent(String databaseId, int limit) {
        ensureTable();
        String sql = """
                SELECT * FROM agentic_memory_files
                WHERE database_id = ?::uuid AND status = 'ACTIVE'
                ORDER BY updated_at DESC
                LIMIT ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            List<FileRow> rows = new ArrayList<>();
            while (rs.next()) rows.add(row(rs));
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list agentic memory files", e);
        }
    }

    private String safePath(String path) {
        String normalized = path == null || path.isBlank() ? "/memory/profile.md" : path.trim().replace('\\', '/');
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        if (normalized.contains("..") || normalized.contains("//") || normalized.length() > 240) {
            throw new IllegalArgumentException("Invalid memory file path");
        }
        return normalized;
    }

    private String firstLine(String content) {
        if (content == null || content.isBlank()) return "";
        String first = content.strip().split("\\R", 2)[0];
        return first.length() > 160 ? first.substring(0, 160) : first;
    }

    private FileRow row(ResultSet rs) throws SQLException {
        Timestamp updated = rs.getTimestamp("updated_at");
        return new FileRow(
                rs.getString("id"),
                rs.getString("database_id"),
                rs.getString("user_id"),
                rs.getString("path"),
                rs.getString("content"),
                rs.getString("summary"),
                rs.getInt("version"),
                rs.getString("status"),
                updated == null ? null : updated.toInstant()
        );
    }

    private void ensureTable() {
        String sql = """
                CREATE TABLE IF NOT EXISTS agentic_memory_files (
                    id TEXT PRIMARY KEY,
                    database_id UUID REFERENCES databases(id) ON DELETE CASCADE,
                    user_id TEXT NOT NULL,
                    path TEXT NOT NULL,
                    content TEXT NOT NULL,
                    summary TEXT,
                    version INT DEFAULT 1,
                    status TEXT DEFAULT 'ACTIVE',
                    search_text tsvector,
                    created_at TIMESTAMPTZ DEFAULT now(),
                    updated_at TIMESTAMPTZ DEFAULT now(),
                    UNIQUE (database_id, path)
                )
                """;
        try (Connection conn = db.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
            st.execute("CREATE INDEX IF NOT EXISTS idx_agentic_files_path ON agentic_memory_files (database_id, path)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_agentic_files_search ON agentic_memory_files USING gin (search_text)");
            st.execute("""
                    CREATE OR REPLACE FUNCTION agentic_files_search_text_trigger() RETURNS trigger AS $$
                    BEGIN
                      NEW.search_text := to_tsvector('english', coalesce(NEW.path, '') || ' ' || coalesce(NEW.summary, '') || ' ' || coalesce(NEW.content, ''));
                      RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql
                    """);
            st.execute("DROP TRIGGER IF EXISTS trg_agentic_files_search_text ON agentic_memory_files");
            st.execute("""
                    CREATE TRIGGER trg_agentic_files_search_text
                    BEFORE INSERT OR UPDATE OF path, summary, content ON agentic_memory_files
                    FOR EACH ROW EXECUTE FUNCTION agentic_files_search_text_trigger()
                    """);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to ensure agentic memory file table", e);
        }
    }

    public record FileRow(
            String id,
            String databaseId,
            String userId,
            String path,
            String content,
            String summary,
            int version,
            String status,
            Instant updatedAt
    ) {}
}
