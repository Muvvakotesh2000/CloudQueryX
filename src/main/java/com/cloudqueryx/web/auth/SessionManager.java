package com.cloudqueryx.web.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Duration sessionTimeout;

    public SessionManager() {
        this(Duration.ofHours(24));
    }

    public SessionManager(Duration sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    public String createSession(String userId, String email) {
        String token = generateToken();
        sessions.put(token, new Session(userId, email, Instant.now()));
        return token;
    }

    public Session validate(String token) {
        if (token == null) return null;
        Session session = sessions.get(token);
        if (session == null) return null;
        if (Duration.between(session.createdAt(), Instant.now()).compareTo(sessionTimeout) > 0) {
            sessions.remove(token);
            return null;
        }
        return session;
    }

    public void invalidate(String token) {
        sessions.remove(token);
    }

    public void cleanExpired() {
        Instant cutoff = Instant.now().minus(sessionTimeout);
        sessions.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record Session(String userId, String email, Instant createdAt) {}
}
