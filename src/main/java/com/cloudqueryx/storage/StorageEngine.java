package com.cloudqueryx.storage;

import com.cloudqueryx.common.Row;
import com.cloudqueryx.common.Schema;

import java.util.List;

public interface StorageEngine {

    void createTable(String name, Schema schema);

    void dropTable(String name);

    void insertRows(String tableName, List<Row> rows);

    List<Row> scanTable(String tableName);

    long getRowCount(String tableName);

    boolean tableExists(String tableName);
}
