package com.cloudqueryx.semantic;

import java.time.Instant;
import java.util.Map;

public record SemanticEntity(
        String id,
        String userId,
        String entityType,
        String name,
        String description,
        Map<String, Object> attributes,
        float[] embedding,
        double confidence,
        String source,
        Instant createdAt,
        Instant updatedAt
) {
    public SemanticEntity {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (entityType == null || entityType.isBlank()) throw new IllegalArgumentException("entityType required");
        if (attributes == null) attributes = Map.of();
        if (confidence < 0) confidence = 1.0;
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }
}
