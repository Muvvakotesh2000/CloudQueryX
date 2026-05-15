package com.cloudqueryx.storage;

import com.cloudqueryx.common.CloudQueryXException;
import com.cloudqueryx.common.Row;
import com.cloudqueryx.common.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryStorageEngine implements StorageEngine {

    private static final Logger log = LoggerFactory.getLogger(InMemoryStorageEngine.class);

    private final Map<String, TableStore> tables = new ConcurrentHashMap<>();

    @Override
    public void createTable(String name, Schema schema) {
        String key = name.toLowerCase();
        if (tables.containsKey(key)) {
            throw new CloudQueryXException(CloudQueryXException.ErrorCode.STORAGE_ERROR,
                    "Table already exists: " + name);
        }
        tables.put(key, new TableStore(schema));
        log.info("Created in-memory table: {}", name);
    }

    @Override
    public void dropTable(String name) {
        tables.remove(name.toLowerCase());
        log.info("Dropped in-memory table: {}", name);
    }

    @Override
    public void insertRows(String tableName, List<Row> rows) {
        TableStore store = getStore(tableName);
        store.rows.addAll(rows);
        log.debug("Inserted {} rows into {}", rows.size(), tableName);
    }

    @Override
    public List<Row> scanTable(String tableName) {
        return Collections.unmodifiableList(getStore(tableName).rows);
    }

    @Override
    public long getRowCount(String tableName) {
        return getStore(tableName).rows.size();
    }

    @Override
    public boolean tableExists(String tableName) {
        return tables.containsKey(tableName.toLowerCase());
    }

    private TableStore getStore(String name) {
        TableStore store = tables.get(name.toLowerCase());
        if (store == null) {
            throw CloudQueryXException.tableNotFound(name);
        }
        return store;
    }

    public long getTotalMemoryUsage() {
        long total = 0;
        for (TableStore store : tables.values()) {
            total += store.rows.size() * 100L;
        }
        return total;
    }

    private static class TableStore {
        final Schema schema;
        final List<Row> rows = Collections.synchronizedList(new ArrayList<>());

        TableStore(Schema schema) {
            this.schema = schema;
        }
    }
}
