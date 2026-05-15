package com.cloudqueryx.execution;

import com.cloudqueryx.catalog.Catalog;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class QueryContext {

    private final String queryId;
    private final Catalog catalog;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicLong rowsScanned = new AtomicLong(0);
    private final AtomicLong rowsReturned = new AtomicLong(0);
    private final AtomicLong bytesProcessed = new AtomicLong(0);
    private final long startTimeMs;
    private final Map<String, Object> properties;
    private long maxMemoryBytes = 512 * 1024 * 1024L;

    public QueryContext(Catalog catalog) {
        this.queryId = UUID.randomUUID().toString();
        this.catalog = catalog;
        this.startTimeMs = System.currentTimeMillis();
        this.properties = new ConcurrentHashMap<>();
    }

    public String getQueryId() { return queryId; }
    public Catalog getCatalog() { return catalog; }
    public boolean isCancelled() { return cancelled.get(); }
    public void cancel() { cancelled.set(true); }
    public long getElapsedMs() { return System.currentTimeMillis() - startTimeMs; }

    public void addRowsScanned(long count) { rowsScanned.addAndGet(count); }
    public void addRowsReturned(long count) { rowsReturned.addAndGet(count); }
    public void addBytesProcessed(long bytes) { bytesProcessed.addAndGet(bytes); }

    public long getRowsScanned() { return rowsScanned.get(); }
    public long getRowsReturned() { return rowsReturned.get(); }
    public long getBytesProcessed() { return bytesProcessed.get(); }
    public long getMaxMemoryBytes() { return maxMemoryBytes; }
    public void setMaxMemoryBytes(long bytes) { this.maxMemoryBytes = bytes; }

    public void setProperty(String key, Object value) { properties.put(key, value); }

    @SuppressWarnings("unchecked")
    public <T> T getProperty(String key, T defaultValue) {
        return (T) properties.getOrDefault(key, defaultValue);
    }

    public String getStatsReport() {
        return String.format("Query %s: elapsed=%dms, scanned=%d rows, returned=%d rows, processed=%d bytes",
                queryId.substring(0, 8), getElapsedMs(), rowsScanned.get(), rowsReturned.get(), bytesProcessed.get());
    }
}
