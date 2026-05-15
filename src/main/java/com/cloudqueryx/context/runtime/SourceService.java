package com.cloudqueryx.context.runtime;

import com.cloudqueryx.embedding.EmbeddingService;
import com.cloudqueryx.repository.ContextChunkRepository;
import com.cloudqueryx.repository.SourceRepository;

import java.util.*;

public class SourceService {
    private final SourceRepository sourceRepo;
    private final ContextChunkRepository chunkRepo;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;

    public SourceService(SourceRepository sourceRepo, ContextChunkRepository chunkRepo,
                         ChunkingService chunkingService, EmbeddingService embeddingService) {
        this.sourceRepo = sourceRepo;
        this.chunkRepo = chunkRepo;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
    }

    public SourceRepository.SourceRow create(String databaseId, String userId, String sourceType,
                                             String sourceName, String content, Map<String, Object> metadata) {
        if (sourceType == null || sourceType.isBlank()) throw new IllegalArgumentException("sourceType required");
        if (sourceName == null || sourceName.isBlank()) throw new IllegalArgumentException("sourceName required");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content required");

        String id = UUID.randomUUID().toString();
        SourceRepository.SourceRow source = new SourceRepository.SourceRow(
                id, databaseId, userId, normalizeType(sourceType), sourceName.trim(), content,
                HashUtil.sha256(content), metadata != null ? metadata : Map.of(),
                1, "ACTIVE", null, null
        );
        sourceRepo.upsert(source);

        List<ContextChunkRepository.ChunkRow> chunks = chunkingService.chunk(databaseId, id, content);
        chunkRepo.replaceChunks(databaseId, id, chunks);
        if (embeddingService != null) {
            for (ContextChunkRepository.ChunkRow chunk : chunks) {
                chunkRepo.upsertEmbedding(databaseId, chunk.id(), embeddingService.embed(chunk.content()), "default");
            }
        }
        return source;
    }

    public Optional<SourceRepository.SourceRow> get(String databaseId, String sourceId) {
        return sourceRepo.get(databaseId, sourceId);
    }

    public List<SourceRepository.SourceRow> list(String databaseId, String userId, int limit) {
        return sourceRepo.list(databaseId, userId, limit);
    }

    private String normalizeType(String sourceType) {
        return sourceType.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }
}
