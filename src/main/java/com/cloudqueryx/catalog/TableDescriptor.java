package com.cloudqueryx.catalog;

import com.cloudqueryx.common.Column;
import com.cloudqueryx.common.Row;
import com.cloudqueryx.common.Schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TableDescriptor {

    private final String name;
    private final Schema schema;
    private final List<Row> data;
    private final TableStatistics statistics;
    private final List<String> partitionColumns;

    public TableDescriptor(String name, Schema schema) {
        this.name = name;
        this.schema = schema;
        this.data = new ArrayList<>();
        this.statistics = new TableStatistics(0, 0);
        this.partitionColumns = new ArrayList<>();
    }

    public String getName() { return name; }
    public Schema getSchema() { return schema; }
    public TableStatistics getStatistics() { return statistics; }
    public int getRowCount() { return data.size(); }
    public List<String> getPartitionColumns() { return Collections.unmodifiableList(partitionColumns); }

    public void addPartitionColumn(String column) {
        partitionColumns.add(column);
    }

    public List<Row> getData() {
        return Collections.unmodifiableList(data);
    }

    public void insertRow(Row row) {
        data.add(row);
        statistics.setRowCount(data.size());
        statistics.setSizeInBytes(data.size() * estimateRowSize());
    }

    public void insertRows(List<Row> rows) {
        data.addAll(rows);
        statistics.setRowCount(data.size());
        statistics.setSizeInBytes(data.size() * estimateRowSize());
    }

    public void clearData() {
        data.clear();
        statistics.setRowCount(0);
        statistics.setSizeInBytes(0);
    }

    public void computeStatistics() {
        statistics.setRowCount(data.size());
        statistics.setSizeInBytes(data.size() * estimateRowSize());

        for (int colIdx = 0; colIdx < schema.getColumnCount(); colIdx++) {
            Column col = schema.getColumn(colIdx);
            long nullCount = 0;
            java.util.Set<Object> distinct = new java.util.HashSet<>();

            for (Row row : data) {
                var val = row.getValue(colIdx);
                if (val.isNull()) {
                    nullCount++;
                } else {
                    distinct.add(val.getRawValue());
                }
            }

            statistics.setColumnStatistics(col.name(),
                    TableStatistics.ColumnStatistics.of(distinct.size(), nullCount));
        }
    }

    private long estimateRowSize() {
        long size = 0;
        for (int i = 0; i < schema.getColumnCount(); i++) {
            size += switch (schema.getColumn(i).type()) {
                case INTEGER -> 4;
                case BIGINT -> 8;
                case DOUBLE -> 8;
                case DECIMAL -> 16;
                case BOOLEAN -> 1;
                case DATE -> 4;
                case TIMESTAMP -> 8;
                case VARCHAR -> 32;
                case NULL -> 0;
            };
        }
        return Math.max(size, 1);
    }

    @Override
    public String toString() {
        return "Table[" + name + " " + schema + ", rows=" + data.size() + "]";
    }
}
