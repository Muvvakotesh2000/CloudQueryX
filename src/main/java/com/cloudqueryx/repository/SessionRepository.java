package com.cloudqueryx.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

public class SessionRepository {

    private static final Logger log = LoggerFactory.getLogger(SessionRepository.class);
    private final DatabaseConfig db;
    private final Duration sessionTimeout;

    public SessionRepository(DatabaseConfig db, Duration sessionTimeout) {
        this.db = db;
        this.sessionTimeout = sessionTimeout;
    }

    public String createSession(String userId, String email) {
        String token = generateToken();
        Instant expiresAt = Instant.now().plus(sessionTimeout);
        String sql = "INSERT INTO sessions (token, user_id, email, expires_at) VALUES (?, ?::uuid, ?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setString(2, userId);
            ps.setString(3, email);
            ps.setTimestamp(4, Timestamp.from(expiresAt));
            ps.executeUpdate();
            return token;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create session", e);
        }
    }

    public Optional<SessionRow> validate(String token) {
        if (token == null) return Optional.empty();
        String sql = "SELECT user_id, email, expires_at FROM sessions WHERE token = ? AND expires_at > now()";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(new SessionRow(
                        rs.getString("user_id"),
                        rs.getString("email"),
                        rs.getTimestamp("expires_at").toInstant()
                ));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to validate session", e);
        }
    }

    public void invalidate(String token) {
        String sql = "DELETE FROM sessions WHERE token = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to invalidate session", e);
        }
    }

    public void cleanExpired() {
        String sql = "DELETE FROM sessions WHERE expires_at < now()";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int deleted = ps.executeUpdate();
            if (deleted > 0) log.debug("Cleaned {} expired sessions", deleted);
        } catch (SQLException e) {
            log.warn("Failed to clean expired sessions", e);
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record SessionRow(String userId, String email, Instant expiresAt) {}
}
