package com.cloudqueryx.semantic;

import java.time.Instant;
import java.util.Map;

public record SemanticRelationship(
        String id,
        String sourceEntityId,
        String targetEntityId,
        String relationshipType,
        Map<String, Object> attributes,
        double weight,
        double confidence,
        String source,
        Instant createdAt
) {
    public SemanticRelationship {
        if (id == null) throw new IllegalArgumentException("id required");
        if (sourceEntityId == null) throw new IllegalArgumentException("sourceEntityId required");
        if (targetEntityId == null) throw new IllegalArgumentException("targetEntityId required");
        if (relationshipType == null) throw new IllegalArgumentException("relationshipType required");
        if (attributes == null) attributes = Map.of();
        if (weight < 0) weight = 1.0;
        if (confidence < 0) confidence = 1.0;
        if (createdAt == null) createdAt = Instant.now();
    }
}
