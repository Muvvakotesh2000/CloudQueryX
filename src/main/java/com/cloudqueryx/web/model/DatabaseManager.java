package com.cloudqueryx.web.model;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class DatabaseManager {

    private static final int DEFAULT_VECTOR_DIMENSION = 384;
    private final Map<String, UserDatabase> databases = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userDatabases = new ConcurrentHashMap<>();

    public UserDatabase create(String userId, String name) {
        String id = UUID.randomUUID().toString();
        UserDatabase db = new UserDatabase(id, userId, name, DEFAULT_VECTOR_DIMENSION);
        databases.put(id, db);
        userDatabases.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(id);
        return db;
    }

    public UserDatabase get(String dbId, String userId) {
        UserDatabase db = databases.get(dbId);
        if (db == null) return null;
        if (!db.getUserId().equals(userId)) return null;
        return db;
    }

    public List<UserDatabase> listForUser(String userId) {
        Set<String> ids = userDatabases.getOrDefault(userId, Set.of());
        return ids.stream()
                .map(databases::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public boolean delete(String dbId, String userId) {
        UserDatabase db = databases.get(dbId);
        if (db == null || !db.getUserId().equals(userId)) return false;
        databases.remove(dbId);
        Set<String> userDbs = userDatabases.get(userId);
        if (userDbs != null) userDbs.remove(dbId);
        return true;
    }
}
