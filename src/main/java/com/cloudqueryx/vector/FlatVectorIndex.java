package com.cloudqueryx.vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class FlatVectorIndex implements VectorIndex {

    private final String indexName;
    private final int dimension;
    private final Map<String, VectorRecord> records = new ConcurrentHashMap<>();

    public FlatVectorIndex(String indexName, int dimension) {
        this.indexName = indexName;
        this.dimension = dimension;
    }

    @Override
    public void insert(VectorRecord record) {
        if (record.dimension() != dimension) {
            throw new IllegalArgumentException("Expected dimension " + dimension + ", got " + record.dimension());
        }
        records.put(record.id(), record);
    }

    @Override
    public void update(String id, float[] newEmbedding, Map<String, Object> newMetadata) {
        VectorRecord existing = records.get(id);
        if (existing == null) throw new NoSuchElementException("Record not found: " + id);
        if (newEmbedding != null && newEmbedding.length != dimension) {
            throw new IllegalArgumentException("Expected dimension " + dimension);
        }

        VectorRecord updated = new VectorRecord(
                existing.id(),
                existing.namespace(),
                newEmbedding != null ? newEmbedding : existing.embedding(),
                newMetadata != null ? newMetadata : existing.metadata(),
                existing.content(),
                existing.createdAt(),
                java.time.Instant.now()
        );
        records.put(id, updated);
    }

    @Override
    public boolean delete(String id) {
        return records.remove(id) != null;
    }

    @Override
    public List<VectorSearchResult> search(float[] query, int topK, SimilarityMetric metric) {
        return search(query, topK, metric, r -> true);
    }

    @Override
    public List<VectorSearchResult> search(float[] query, int topK, SimilarityMetric metric,
                                            Predicate<VectorRecord> filter) {
        if (query.length != dimension) {
            throw new IllegalArgumentException("Query dimension " + query.length + " != index dimension " + dimension);
        }

        PriorityQueue<VectorSearchResult> heap = new PriorityQueue<>(
                Comparator.comparingDouble(VectorSearchResult::score));

        for (VectorRecord record : records.values()) {
            if (!filter.test(record)) continue;

            float score = metric.compute(query, record.embedding());
            if (heap.size() < topK) {
                heap.offer(new VectorSearchResult(record, score));
            } else if (score > heap.peek().score()) {
                heap.poll();
                heap.offer(new VectorSearchResult(record, score));
            }
        }

        List<VectorSearchResult> results = new ArrayList<>(heap);
        results.sort(Comparator.comparingDouble(VectorSearchResult::score).reversed());
        return results;
    }

    @Override
    public VectorRecord get(String id) {
        return records.get(id);
    }

    @Override
    public int size() {
        return records.size();
    }

    @Override
    public String name() {
        return indexName;
    }

    public int getDimension() {
        return dimension;
    }
}
