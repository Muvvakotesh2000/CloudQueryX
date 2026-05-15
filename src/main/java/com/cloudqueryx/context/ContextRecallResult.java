package com.cloudqueryx.context;

import java.util.Map;

public record ContextRecallResult(
        String id,
        String source,
        String type,
        String content,
        double score,
        Map<String, Object> metadata,
        String explanation
) {
    public ContextRecallResult {
        if (metadata == null) metadata = Map.of();
    }
}
