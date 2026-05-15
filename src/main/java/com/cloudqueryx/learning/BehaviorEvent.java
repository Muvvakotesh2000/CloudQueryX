package com.cloudqueryx.learning;

import java.time.Instant;
import java.util.Map;

public record BehaviorEvent(
        String id,
        String userId,
        String eventType,
        String action,
        Map<String, Object> properties,
        String sessionId,
        Instant timestamp
) {
    public BehaviorEvent {
        if (id == null) throw new IllegalArgumentException("id required");
        if (eventType == null) throw new IllegalArgumentException("eventType required");
        if (properties == null) properties = Map.of();
        if (timestamp == null) timestamp = Instant.now();
    }
}
