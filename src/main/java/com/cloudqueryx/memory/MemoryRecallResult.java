package com.cloudqueryx.memory;

public record MemoryRecallResult(
        MemoryRecord record,
        float similarity,
        double relevanceScore,
        String explanation
) {}
