package com.cloudqueryx.context.runtime;

import com.cloudqueryx.repository.ContextChunkRepository;

import java.util.*;

public class ChunkingService {
    private static final int TARGET_TOKENS = 450;
    private static final int MAX_TOKENS = 650;

    private final TokenEstimator tokenEstimator;

    public ChunkingService(TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    public List<ContextChunkRepository.ChunkRow> chunk(String databaseId, String sourceId, String content) {
        List<ContextChunkRepository.ChunkRow> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) return chunks;

        String[] paragraphs = content.split("\\R\\s*\\R");
        StringBuilder current = new StringBuilder();
        int index = 0;

        for (String paragraph : paragraphs) {
            if (paragraph.isBlank()) continue;
            String candidate = current.isEmpty() ? paragraph.trim() : current + "\n\n" + paragraph.trim();
            int candidateTokens = tokenEstimator.estimate(candidate);
            if (candidateTokens > MAX_TOKENS && !current.isEmpty()) {
                chunks.add(row(databaseId, sourceId, index++, current.toString()));
                current.setLength(0);
                appendSplitParagraph(chunks, databaseId, sourceId, paragraph.trim(), index);
                index = chunks.size();
            } else {
                current.setLength(0);
                current.append(candidate);
                if (candidateTokens >= TARGET_TOKENS) {
                    chunks.add(row(databaseId, sourceId, index++, current.toString()));
                    current.setLength(0);
                }
            }
        }

        if (!current.isEmpty()) {
            chunks.add(row(databaseId, sourceId, index, current.toString()));
        }
        return chunks;
    }

    private void appendSplitParagraph(List<ContextChunkRepository.ChunkRow> chunks, String databaseId,
                                      String sourceId, String paragraph, int startIndex) {
        String[] lines = paragraph.split("\\R");
        StringBuilder current = new StringBuilder();
        int index = startIndex;
        for (String line : lines) {
            String candidate = current.isEmpty() ? line : current + "\n" + line;
            if (tokenEstimator.estimate(candidate) > MAX_TOKENS && !current.isEmpty()) {
                chunks.add(row(databaseId, sourceId, index++, current.toString()));
                current.setLength(0);
                current.append(line);
            } else {
                current.setLength(0);
                current.append(candidate);
            }
        }
        if (!current.isEmpty()) chunks.add(row(databaseId, sourceId, index, current.toString()));
    }

    private ContextChunkRepository.ChunkRow row(String databaseId, String sourceId, int index, String content) {
        String id = sourceId + "-chunk-" + index;
        return new ContextChunkRepository.ChunkRow(
                id, databaseId, sourceId, index, content,
                tokenEstimator.estimate(content), HashUtil.sha256(content),
                Map.of(), null, null, null, null
        );
    }
}
