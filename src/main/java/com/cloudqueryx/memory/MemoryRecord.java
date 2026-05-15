package com.cloudqueryx.memory;

import java.time.Instant;
import java.util.Map;

public record MemoryRecord(
        String id,
        String userId,
        String namespace,
        MemoryType type,
        String content,
        Map<String, Object> metadata,
        float[] embedding,
        double importance,
        double confidence,
        double recency,
        String source,
        Instant createdAt,
        Instant updatedAt,
        Instant accessedAt,
        long accessCount,
        // Bitemporal fields (Zep-inspired)
        Instant validFrom,
        Instant validUntil,
        Instant expiredAt
) {
    public MemoryRecord(String id, String userId, String namespace, MemoryType type,
                        String content, Map<String, Object> metadata, float[] embedding,
                        double importance, double confidence, double recency, String source,
                        Instant createdAt, Instant updatedAt, Instant accessedAt, long accessCount) {
        this(id, userId, namespace, type, content, metadata, embedding,
                importance, confidence, recency, source,
                createdAt, updatedAt, accessedAt, accessCount,
                null, null, null);
    }

    public MemoryRecord {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (type == null) throw new IllegalArgumentException("type required");
        if (namespace == null) namespace = "default";
        if (metadata == null) metadata = Map.of();
        if (importance < 0) importance = 0.5;
        if (confidence < 0) confidence = 1.0;
        if (recency < 0) recency = 1.0;
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
        if (accessedAt == null) accessedAt = createdAt;
        if (validFrom == null) validFrom = createdAt;
    }

    public double computeRelevanceScore(double recencyWeight, double importanceWeight, double confidenceWeight) {
        return recency * recencyWeight + importance * importanceWeight + confidence * confidenceWeight;
    }

    public boolean isValid() {
        if (expiredAt != null) return false;
        if (validUntil != null && Instant.now().isAfter(validUntil)) return false;
        return true;
    }

    public MemoryRecord withAccess() {
        return new MemoryRecord(id, userId, namespace, type, content, metadata, embedding,
                importance, confidence, recency, source, createdAt, updatedAt,
                Instant.now(), accessCount + 1, validFrom, validUntil, expiredAt);
    }

    public MemoryRecord withImportance(double newImportance) {
        return new MemoryRecord(id, userId, namespace, type, content, metadata, embedding,
                newImportance, confidence, recency, source, createdAt, Instant.now(),
                accessedAt, accessCount, validFrom, validUntil, expiredAt);
    }

    public MemoryRecord withEmbedding(float[] newEmbedding) {
        return new MemoryRecord(id, userId, namespace, type, content, metadata, newEmbedding,
                importance, confidence, recency, source, createdAt, Instant.now(),
                accessedAt, accessCount, validFrom, validUntil, expiredAt);
    }

    public MemoryRecord withDecayedRecency(double decayFactor) {
        double newRecency = recency * decayFactor;
        return new MemoryRecord(id, userId, namespace, type, content, metadata, embedding,
                importance, confidence, newRecency, source, createdAt, updatedAt,
                accessedAt, accessCount, validFrom, validUntil, expiredAt);
    }

    public MemoryRecord withExpired() {
        return new MemoryRecord(id, userId, namespace, type, content, metadata, embedding,
                importance, confidence, recency, source, createdAt, Instant.now(),
                accessedAt, accessCount, validFrom, validUntil, Instant.now());
    }

    public MemoryRecord withValidUntil(Instant until) {
        return new MemoryRecord(id, userId, namespace, type, content, metadata, embedding,
                importance, confidence, recency, source, createdAt, Instant.now(),
                accessedAt, accessCount, validFrom, until, expiredAt);
    }
}
