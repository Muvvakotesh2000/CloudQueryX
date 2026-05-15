package com.cloudqueryx.multimodal;

import java.time.Instant;
import java.util.Map;

public record MultimodalAsset(
        String id,
        String userId,
        ModalityType modality,
        String title,
        String sourceUri,
        String mimeType,
        String extractedText,
        String summary,
        Map<String, Object> metadata,
        float[] embedding,
        String embeddingModel,
        long sizeBytes,
        Instant createdAt,
        Instant updatedAt
) {
    public MultimodalAsset {
        if (id == null) throw new IllegalArgumentException("id required");
        if (modality == null) throw new IllegalArgumentException("modality required");
        if (metadata == null) metadata = Map.of();
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }
}
