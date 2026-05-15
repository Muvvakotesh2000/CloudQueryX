package com.cloudqueryx.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class WebhookRepository {

    private static final Logger log = LoggerFactory.getLogger(WebhookRepository.class);
    private final DatabaseConfig db;

    public WebhookRepository(DatabaseConfig db) {
        this.db = db;
    }

    public WebhookRow create(String databaseId, String url, String[] events, String secret) {
        String sql = """
                INSERT INTO webhooks (database_id, url, events, secret)
                VALUES (?::uuid, ?, ?, ?)
                RETURNING id, database_id, url, events, secret, active, created_at, updated_at
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, url);
            ps.setArray(3, conn.createArrayOf("text", events));
            ps.setString(4, secret);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rowFromResultSet(rs);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create webhook", e);
        }
    }

    public List<WebhookRow> listForDatabase(String databaseId) {
        String sql = "SELECT * FROM webhooks WHERE database_id = ?::uuid ORDER BY created_at";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ResultSet rs = ps.executeQuery();
            List<WebhookRow> rows = new ArrayList<>();
            while (rs.next()) rows.add(rowFromResultSet(rs));
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list webhooks", e);
        }
    }

    public List<WebhookRow> getActiveForEvent(String databaseId, String eventType) {
        String sql = """
                SELECT * FROM webhooks
                WHERE database_id = ?::uuid AND active = true AND ? = ANY(events)
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, eventType);
            ResultSet rs = ps.executeQuery();
            List<WebhookRow> rows = new ArrayList<>();
            while (rs.next()) rows.add(rowFromResultSet(rs));
            return rows;
        } catch (SQLException e) {
            log.warn("Failed to query webhooks for event {}: {}", eventType, e.getMessage());
            return List.of();
        }
    }

    public boolean delete(String databaseId, String webhookId) {
        String sql = "DELETE FROM webhooks WHERE database_id = ?::uuid AND id = ?::uuid";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseId);
            ps.setString(2, webhookId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete webhook", e);
        }
    }

    public boolean setActive(String databaseId, String webhookId, boolean active) {
        String sql = "UPDATE webhooks SET active = ?, updated_at = now() WHERE database_id = ?::uuid AND id = ?::uuid";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, active);
            ps.setString(2, databaseId);
            ps.setString(3, webhookId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update webhook", e);
        }
    }

    private WebhookRow rowFromResultSet(ResultSet rs) throws SQLException {
        Array eventsArr = rs.getArray("events");
        String[] events = eventsArr != null ? (String[]) eventsArr.getArray() : new String[0];
        return new WebhookRow(
                rs.getString("id"),
                rs.getString("database_id"),
                rs.getString("url"),
                events,
                rs.getString("secret"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    public record WebhookRow(
            String id, String databaseId, String url, String[] events,
            String secret, boolean active, Instant createdAt, Instant updatedAt
    ) {}
}
