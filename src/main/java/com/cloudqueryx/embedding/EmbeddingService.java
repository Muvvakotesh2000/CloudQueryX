package com.cloudqueryx.embedding;

public interface EmbeddingService {
    float[] embed(String text);
    int dimensions();
}
