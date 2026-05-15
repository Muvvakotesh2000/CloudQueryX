package com.cloudqueryx.memory;

import com.cloudqueryx.vector.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class MemoryStore {

    private final Map<String, MemoryRecord> records = new ConcurrentHashMap<>();
    private final VectorStore vectorStore;
    private final double recencyDecayRate;

    public MemoryStore(VectorStore vectorStore) {
        this(vectorStore, 0.995);
    }

    public MemoryStore(VectorStore vectorStore, double recencyDecayRate) {
        this.vectorStore = vectorStore;
        this.recencyDecayRate = recencyDecayRate;
    }

    public MemoryRecord store(MemoryRecord record) {
        records.put(record.id(), record);
        if (record.embedding() != null) {
            VectorRecord vr = new VectorRecord(
                    record.id(), record.namespace(), record.embedding(),
                    buildVectorMetadata(record), record.content(), record.createdAt(), null
            );
            vectorStore.insert(record.namespace(), vr);
        }
        return record;
    }

    public MemoryRecord recall(String id) {
        MemoryRecord record = records.get(id);
        if (record == null) return null;
        MemoryRecord accessed = record.withAccess();
        records.put(id, accessed);
        return accessed;
    }

    public List<MemoryRecallResult> recallSimilar(String namespace, float[] queryEmbedding,
                                                    int topK, MemoryType typeFilter) {
        Predicate<VectorRecord> filter = typeFilter != null
                ? vr -> typeFilter.name().equals(vr.metadata().get("type"))
                : vr -> true;

        List<VectorSearchResult> vectorResults = vectorStore.search(
                namespace, queryEmbedding, topK * 2, SimilarityMetric.COSINE, filter);

        List<MemoryRecallResult> results = new ArrayList<>();
        for (VectorSearchResult vsr : vectorResults) {
            MemoryRecord mem = records.get(vsr.record().id());
            if (mem == null) continue;
            mem = mem.withAccess();
            records.put(mem.id(), mem);

            double relevance = computeRelevance(mem, vsr.score());
            String explanation = buildExplanation(mem, vsr.score(), relevance);
            results.add(new MemoryRecallResult(mem, vsr.score(), relevance, explanation));
        }

        results.sort(Comparator.comparingDouble(MemoryRecallResult::relevanceScore).reversed());
        return results.stream().limit(topK).collect(Collectors.toList());
    }

    public boolean updateImportance(String id, double newImportance) {
        MemoryRecord record = records.get(id);
        if (record == null) return false;
        records.put(id, record.withImportance(newImportance));
        return true;
    }

    public boolean forget(String id) {
        MemoryRecord removed = records.remove(id);
        if (removed == null) return false;
        vectorStore.delete(removed.namespace(), id);
        return true;
    }

    public List<MemoryRecord> getByUser(String userId) {
        return records.values().stream()
                .filter(r -> userId.equals(r.userId()))
                .collect(Collectors.toList());
    }

    public List<MemoryRecord> getByType(MemoryType type) {
        return records.values().stream()
                .filter(r -> r.type() == type)
                .collect(Collectors.toList());
    }

    public List<MemoryRecord> getUserContext(String userId, int maxRecords) {
        return records.values().stream()
                .filter(r -> userId.equals(r.userId()))
                .sorted(Comparator.comparingDouble(MemoryRecord::importance).reversed()
                        .thenComparing(Comparator.comparing(MemoryRecord::updatedAt).reversed()))
                .limit(maxRecords)
                .collect(Collectors.toList());
    }

    public String summarizeMemories(String userId, MemoryType type) {
        List<MemoryRecord> memories = records.values().stream()
                .filter(r -> userId.equals(r.userId()) && (type == null || r.type() == type))
                .sorted(Comparator.comparing(MemoryRecord::createdAt))
                .toList();

        if (memories.isEmpty()) return "No memories found.";

        StringBuilder sb = new StringBuilder();
        sb.append("Memory summary for user ").append(userId).append(":\n");
        sb.append("Total memories: ").append(memories.size()).append("\n");

        Map<MemoryType, Long> typeCounts = memories.stream()
                .collect(Collectors.groupingBy(MemoryRecord::type, Collectors.counting()));
        typeCounts.forEach((t, c) -> sb.append("  ").append(t).append(": ").append(c).append("\n"));

        sb.append("\nMost important:\n");
        memories.stream()
                .sorted(Comparator.comparingDouble(MemoryRecord::importance).reversed())
                .limit(5)
                .forEach(m -> sb.append("  [").append(m.type()).append("] ")
                        .append(truncate(m.content(), 80)).append("\n"));

        return sb.toString();
    }

    public void applyRecencyDecay() {
        Instant now = Instant.now();
        for (Map.Entry<String, MemoryRecord> entry : records.entrySet()) {
            MemoryRecord r = entry.getValue();
            long hoursSinceAccess = Duration.between(r.accessedAt(), now).toHours();
            double decay = Math.pow(recencyDecayRate, hoursSinceAccess);
            entry.setValue(r.withDecayedRecency(decay));
        }
    }

    public int size() {
        return records.size();
    }

    private double computeRelevance(MemoryRecord record, float similarity) {
        return similarity * 0.4 + record.importance() * 0.3
                + record.recency() * 0.2 + record.confidence() * 0.1;
    }

    private String buildExplanation(MemoryRecord record, float similarity, double relevance) {
        return String.format("Recalled because: similarity=%.3f, importance=%.2f, recency=%.2f, confidence=%.2f → relevance=%.3f",
                similarity, record.importance(), record.recency(), record.confidence(), relevance);
    }

    private Map<String, Object> buildVectorMetadata(MemoryRecord record) {
        Map<String, Object> meta = new HashMap<>(record.metadata());
        meta.put("type", record.type().name());
        if (record.userId() != null) meta.put("userId", record.userId());
        meta.put("importance", record.importance());
        return meta;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
