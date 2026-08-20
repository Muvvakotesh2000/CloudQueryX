package com.cloudqueryx.repository;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class CodingProjectRepository {

    private final DatabaseConfig db;

    public CodingProjectRepository(DatabaseConfig db) {
        this.db = db;
    }

    public ProjectRow create(String databaseId, String userId, String name, String description,
                             String sourceType, String githubRepoUrl) {
        String sql = """
                INSERT INTO coding_projects (database_id, owner_user_id, name, description, source_type, github_repo_url)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?)
                RETURNING id, database_id, owner_user_id, name, description, source_type, github_repo_url, status, created_at, updated_at
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, userId);
            ps.setString(3, name);
            ps.setString(4, description != null ? description : "");
            ps.setString(5, sourceType != null ? sourceType : "upload");
            ps.setString(6, githubRepoUrl);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return project(rs);
            throw new IllegalStateException("INSERT RETURNING produced no rows");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create coding project", e);
        }
    }

    public Optional<ProjectRow> get(String projectId, String databaseId, String userId) {
        String sql = """
                SELECT id, database_id, owner_user_id, name, description, source_type, github_repo_url, status, created_at, updated_at
                FROM coding_projects
                WHERE id = ?::uuid AND database_id = ?::uuid AND owner_user_id = ?::uuid AND status <> 'DELETED'
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, databaseId);
            ps.setString(3, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(project(rs));
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get coding project", e);
        }
    }

    public List<ProjectRow> list(String databaseId, String userId, int limit) {
        String sql = """
                SELECT id, database_id, owner_user_id, name, description, source_type, github_repo_url, status, created_at, updated_at
                FROM coding_projects
                WHERE database_id = ?::uuid AND owner_user_id = ?::uuid AND status <> 'DELETED'
                ORDER BY updated_at DESC
                LIMIT ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, userId);
            ps.setInt(3, limit);
            ResultSet rs = ps.executeQuery();
            List<ProjectRow> rows = new ArrayList<>();
            while (rs.next()) rows.add(project(rs));
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list coding projects", e);
        }
    }

    public FileRow upsertFile(String projectId, String databaseId, String userId, String sourceId,
                              String path, String language, String s3Key, String contentHash, long sizeBytes) {
        String sql = """
                INSERT INTO project_files (project_id, database_id, owner_user_id, source_id, path, language, s3_key, content_hash, size_bytes)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (project_id, path)
                DO UPDATE SET source_id = EXCLUDED.source_id,
                              language = EXCLUDED.language,
                              s3_key = EXCLUDED.s3_key,
                              content_hash = EXCLUDED.content_hash,
                              size_bytes = EXCLUDED.size_bytes,
                              version = project_files.version + 1,
                              status = 'ACTIVE',
                              updated_at = now()
                RETURNING id, project_id, database_id, owner_user_id, source_id, path, language, s3_key, content_hash, size_bytes, version, status, created_at, updated_at
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, databaseId);
            ps.setString(3, userId);
            ps.setString(4, sourceId);
            ps.setString(5, normalizePath(path));
            ps.setString(6, language != null ? language : "text");
            ps.setString(7, s3Key);
            ps.setString(8, contentHash);
            ps.setLong(9, sizeBytes);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                FileRow row = file(rs);
                insertVersion(row);
                return row;
            }
            throw new IllegalStateException("UPSERT RETURNING produced no rows");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert project file", e);
        }
    }

    public List<FileRow> listFiles(String projectId, String databaseId, String userId) {
        String sql = """
                SELECT id, project_id, database_id, owner_user_id, source_id, path, language, s3_key, content_hash, size_bytes, version, status, created_at, updated_at
                FROM project_files
                WHERE project_id = ?::uuid AND database_id = ?::uuid AND owner_user_id = ?::uuid AND status <> 'DELETED'
                ORDER BY path
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, databaseId);
            ps.setString(3, userId);
            ResultSet rs = ps.executeQuery();
            List<FileRow> rows = new ArrayList<>();
            while (rs.next()) rows.add(file(rs));
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list project files", e);
        }
    }

    private void insertVersion(FileRow row) throws SQLException {
        String sql = """
                INSERT INTO project_file_versions (file_id, project_id, database_id, owner_user_id, s3_key, content_hash, version)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?)
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, row.id());
            ps.setString(2, row.projectId());
            ps.setString(3, row.databaseId());
            ps.setString(4, row.ownerUserId());
            ps.setString(5, row.s3Key());
            ps.setString(6, row.contentHash());
            ps.setInt(7, row.version());
            ps.executeUpdate();
        }
    }

    private ProjectRow project(ResultSet rs) throws SQLException {
        return new ProjectRow(
                rs.getString("id"),
                rs.getString("database_id"),
                rs.getString("owner_user_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("source_type"),
                rs.getString("github_repo_url"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private FileRow file(ResultSet rs) throws SQLException {
        return new FileRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("database_id"),
                rs.getString("owner_user_id"),
                rs.getString("source_id"),
                rs.getString("path"),
                rs.getString("language"),
                rs.getString("s3_key"),
                rs.getString("content_hash"),
                rs.getLong("size_bytes"),
                rs.getInt("version"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    public static String normalizePath(String path) {
        String value = path == null ? "untitled.txt" : path.trim().replace('\\', '/');
        value = value.replaceAll("/+", "/");
        while (value.startsWith("/")) value = value.substring(1);
        if (value.contains("..")) throw new IllegalArgumentException("File path cannot contain '..'");
        return value.isBlank() ? "untitled.txt" : value;
    }

    public record ProjectRow(String id, String databaseId, String ownerUserId, String name,
                             String description, String sourceType, String githubRepoUrl,
                             String status, Instant createdAt, Instant updatedAt) {}

    public record FileRow(String id, String projectId, String databaseId, String ownerUserId,
                          String sourceId, String path, String language, String s3Key,
                          String contentHash, long sizeBytes, int version, String status,
                          Instant createdAt, Instant updatedAt) {}
}
