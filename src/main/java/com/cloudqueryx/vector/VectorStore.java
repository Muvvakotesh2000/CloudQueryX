package com.cloudqueryx.vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class VectorStore {

    private final Map<String, VectorIndex> namespaceIndices = new ConcurrentHashMap<>();
    private final int defaultDimension;

    public VectorStore(int defaultDimension) {
        this.defaultDimension = defaultDimension;
    }

    public VectorIndex getOrCreateNamespace(String namespace) {
        return namespaceIndices.computeIfAbsent(namespace,
                ns -> new FlatVectorIndex(ns, defaultDimension));
    }

    public VectorIndex getNamespace(String namespace) {
        VectorIndex idx = namespaceIndices.get(namespace);
        if (idx == null) throw new NoSuchElementException("Namespace not found: " + namespace);
        return idx;
    }

    public void insert(String namespace, VectorRecord record) {
        getOrCreateNamespace(namespace).insert(record);
    }

    public List<VectorSearchResult> search(String namespace, float[] query, int topK,
                                            SimilarityMetric metric) {
        return getOrCreateNamespace(namespace).search(query, topK, metric);
    }

    public List<VectorSearchResult> search(String namespace, float[] query, int topK,
                                            SimilarityMetric metric, Predicate<VectorRecord> filter) {
        return getOrCreateNamespace(namespace).search(query, topK, metric, filter);
    }

    public List<VectorSearchResult> hybridSearch(String namespace, float[] query, int topK,
                                                  SimilarityMetric metric,
                                                  String keyword,
                                                  Predicate<VectorRecord> metadataFilter) {
        Predicate<VectorRecord> combinedFilter = record -> {
            if (metadataFilter != null && !metadataFilter.test(record)) return false;
            if (keyword != null && !keyword.isEmpty()) {
                String content = record.content();
                if (content == null || !content.toLowerCase().contains(keyword.toLowerCase())) {
                    return false;
                }
            }
            return true;
        };
        return getOrCreateNamespace(namespace).search(query, topK, metric, combinedFilter);
    }

    public boolean delete(String namespace, String id) {
        VectorIndex idx = namespaceIndices.get(namespace);
        return idx != null && idx.delete(id);
    }

    public Set<String> listNamespaces() {
        return Collections.unmodifiableSet(namespaceIndices.keySet());
    }

    public int totalRecords() {
        return namespaceIndices.values().stream().mapToInt(VectorIndex::size).sum();
    }
}
