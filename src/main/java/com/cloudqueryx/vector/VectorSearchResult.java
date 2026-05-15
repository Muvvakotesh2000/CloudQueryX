package com.cloudqueryx.vector;

public record VectorSearchResult(
        VectorRecord record,
        float score
) implements Comparable<VectorSearchResult> {
    @Override
    public int compareTo(VectorSearchResult other) {
        return Float.compare(other.score, this.score);
    }
}
