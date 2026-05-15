package com.cloudqueryx.web.auth;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class UserStore {

    private final Map<String, User> usersById = new ConcurrentHashMap<>();
    private final Map<String, String> emailToId = new ConcurrentHashMap<>();

    public User register(String email, String password) {
        if (emailToId.containsKey(email.toLowerCase())) {
            throw new IllegalArgumentException("Email already registered");
        }
        String id = UUID.randomUUID().toString();
        String passwordHash = PasswordHasher.hash(password);
        User user = new User(id, email.toLowerCase(), passwordHash, Instant.now());
        usersById.put(id, user);
        emailToId.put(email.toLowerCase(), id);
        return user;
    }

    public User authenticate(String email, String password) {
        String userId = emailToId.get(email.toLowerCase());
        if (userId == null) return null;
        User user = usersById.get(userId);
        if (user == null) return null;
        if (!PasswordHasher.verify(password, user.passwordHash())) return null;
        return user;
    }

    public User getById(String id) {
        return usersById.get(id);
    }

    public User getByEmail(String email) {
        String id = emailToId.get(email.toLowerCase());
        return id != null ? usersById.get(id) : null;
    }

    public int size() {
        return usersById.size();
    }

    public record User(String id, String email, String passwordHash, Instant createdAt) {
        public Map<String, Object> toPublicMap() {
            return Map.of("id", id, "email", email, "createdAt", createdAt.toString());
        }
    }
}
