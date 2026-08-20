package com.cloudqueryx.context.runtime;

import java.time.Instant;
import java.util.Map;

public record TraceEvent(
        String requestId,
        String bundleId,
        String stage,
        Map<String, Object> payload,
        Instant timestamp
) {
    public TraceEvent(String requestId, String stage, Map<String, Object> payload) {
        this(requestId, null, stage, payload, Instant.now());
    }

    public TraceEvent withBundleId(String newBundleId) {
        return new TraceEvent(requestId, newBundleId, stage, payload, timestamp);
    }
}
