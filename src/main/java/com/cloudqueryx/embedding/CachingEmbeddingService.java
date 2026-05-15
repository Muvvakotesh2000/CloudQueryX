package com.cloudqueryx.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class CachingEmbeddingService implements EmbeddingService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CachingEmbeddingService.class);

    private final EmbeddingService delegate;
    private final Map<String, float[]> cache;
    private long hits;
    private long misses;

    public CachingEmbeddingService(EmbeddingService delegate, int maxSize) {
        this.delegate = delegate;
        this.cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                return size() > maxSize;
            }
        };
        log.info("Embedding cache initialized (maxSize={})", maxSize);
    }

    @Override
    public synchronized float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return delegate.embed(text);
        }
        float[] cached = cache.get(text);
        if (cached != null) {
            hits++;
            return cached;
        }
        misses++;
        float[] result = delegate.embed(text);
        cache.put(text, result);
        return result;
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    public synchronized long hits() { return hits; }
    public synchronized long misses() { return misses; }
    public synchronized int size() { return cache.size(); }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable ac) {
            ac.close();
        }
        log.info("Embedding cache closed (hits={}, misses={}, size={})", hits, misses, cache.size());
    }
}
