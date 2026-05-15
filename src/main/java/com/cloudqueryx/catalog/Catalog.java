package com.cloudqueryx.catalog;

import com.cloudqueryx.common.CloudQueryXException;
import com.cloudqueryx.common.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Catalog {

    private static final Logger log = LoggerFactory.getLogger(Catalog.class);
    private final Map<String, TableDescriptor> tables = new ConcurrentHashMap<>();

    public void registerTable(TableDescriptor table) {
        tables.put(table.getName().toLowerCase(), table);
        log.info("Registered table: {} with {} columns",
                table.getName(), table.getSchema().getColumnCount());
    }

    public TableDescriptor getTable(String name) {
        TableDescriptor table = tables.get(name.toLowerCase());
        if (table == null) {
            throw CloudQueryXException.tableNotFound(name);
        }
        return table;
    }

    public boolean hasTable(String name) {
        return tables.containsKey(name.toLowerCase());
    }

    public void dropTable(String name) {
        TableDescriptor removed = tables.remove(name.toLowerCase());
        if (removed != null) {
            log.info("Dropped table: {}", name);
        }
    }

    public Collection<TableDescriptor> getAllTables() {
        return Collections.unmodifiableCollection(tables.values());
    }

    public Set<String> getTableNames() {
        return Collections.unmodifiableSet(tables.keySet());
    }

    public Set<String> listTables() {
        return getTableNames();
    }

    public Schema getTableSchema(String tableName) {
        return getTable(tableName).getSchema();
    }

    public TableStatistics getTableStatistics(String tableName) {
        return getTable(tableName).getStatistics();
    }

    public void analyzeTable(String tableName) {
        TableDescriptor table = getTable(tableName);
        table.computeStatistics();
        log.info("Analyzed table {} - {} rows", tableName, table.getStatistics().getRowCount());
    }

    public void analyzeAllTables() {
        for (TableDescriptor table : tables.values()) {
            table.computeStatistics();
        }
        log.info("Analyzed {} tables", tables.size());
    }
}
