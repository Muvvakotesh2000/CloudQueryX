package com.cloudqueryx.vector;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public interface VectorIndex {

    void insert(VectorRecord record);

    void update(String id, float[] newEmbedding, Map<String, Object> newMetadata);

    boolean delete(String id);

    List<VectorSearchResult> search(float[] query, int topK, SimilarityMetric metric);

    List<VectorSearchResult> search(float[] query, int topK, SimilarityMetric metric,
                                     Predicate<VectorRecord> filter);

    VectorRecord get(String id);

    int size();

    String name();
}
