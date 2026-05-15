package com.cloudqueryx.context.runtime;

public interface ContextCompressor {
    String compress(String content, int maxTokens, String query);
}
