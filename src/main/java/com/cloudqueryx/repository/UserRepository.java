package com.cloudqueryx.repository;

import com.cloudqueryx.web.auth.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public class UserRepository {

    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);
    private final DatabaseConfig db;

    public UserRepository(DatabaseConfig db) {
        this.db = db;
    }

    public UserRow register(String email, String password) {
        String passwordHash = PasswordHasher.hash(password);
        String sql = "INSERT INTO users (email, password_hash) VALUES (?, ?) RETURNING id, email, created_at";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email.toLowerCase());
            ps.setString(2, passwordHash);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new UserRow(
                        rs.getString("id"),
                        rs.getString("email"),
                        passwordHash,
                        rs.getTimestamp("created_at").toInstant()
                );
            }
            throw new IllegalStateException("INSERT RETURNING produced no rows");
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                throw new IllegalArgumentException("Email already registered");
            }
            throw new RuntimeException("Failed to register user", e);
        }
    }

    public Optional<UserRow> authenticate(String email, String password) {
        String sql = "SELECT id, email, password_hash, created_at FROM users WHERE email = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email.toLowerCase());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String hash = rs.getString("password_hash");
                if (PasswordHasher.verify(password, hash)) {
                    return Optional.of(new UserRow(
                            rs.getString("id"),
                            rs.getString("email"),
                            hash,
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Authentication failed", e);
        }
    }

    public Optional<UserRow> getById(String id) {
        String sql = "SELECT id, email, password_hash, created_at FROM users WHERE id = ?::uuid";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(new UserRow(
                        rs.getString("id"),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getTimestamp("created_at").toInstant()
                ));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch user", e);
        }
    }

    public record UserRow(String id, String email, String passwordHash, Instant createdAt) {
        public Map<String, Object> toPublicMap() {
            return Map.of("id", id, "email", email, "createdAt", createdAt.toString());
        }
    }
}
