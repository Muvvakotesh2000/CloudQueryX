package com.cloudqueryx.vector;

import java.time.Instant;
import java.util.Map;

public record VectorRecord(
        String id,
        String namespace,
        float[] embedding,
        Map<String, Object> metadata,
        String content,
        Instant createdAt,
        Instant updatedAt
) {
    public VectorRecord {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (embedding == null || embedding.length == 0) throw new IllegalArgumentException("embedding required");
        if (namespace == null) namespace = "default";
        if (metadata == null) metadata = Map.of();
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }

    public int dimension() {
        return embedding.length;
    }
}
