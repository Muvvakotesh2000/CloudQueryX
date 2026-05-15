package com.cloudqueryx.context.runtime;

import java.time.Instant;
import java.util.Map;

public record RetrievalResult(
        String type,
        String id,
        String sourceId,
        String chunkId,
        String memoryId,
        String sourceName,
        String sourceType,
        String content,
        int tokenEstimate,
        double vectorScore,
        double textScore,
        double freshnessScore,
        double finalScore,
        String reason,
        Map<String, Object> metadata,
        Instant updatedAt
) {}
