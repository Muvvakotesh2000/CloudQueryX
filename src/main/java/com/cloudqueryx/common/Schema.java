package com.cloudqueryx.common;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Schema {

    private final List<Column> columns;
    private final Map<String, Integer> nameIndex;

    public Schema(List<Column> columns) {
        this.columns = List.copyOf(columns);
        this.nameIndex = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            Column col = columns.get(i);
            nameIndex.put(col.name().toLowerCase(), i);
            if (col.tableName() != null) {
                nameIndex.put(col.qualifiedName().toLowerCase(), i);
            }
        }
    }

    public static Schema of(Column... columns) {
        return new Schema(Arrays.asList(columns));
    }

    public List<Column> getColumns() {
        return columns;
    }

    public int getColumnCount() {
        return columns.size();
    }

    public Column getColumn(int index) {
        return columns.get(index);
    }

    public Column getColumn(String name) {
        Integer idx = resolveIndex(name);
        if (idx == null) {
            throw new IllegalArgumentException("Column not found: " + name);
        }
        return columns.get(idx);
    }

    public int getColumnIndex(String name) {
        Integer idx = resolveIndex(name);
        if (idx == null) {
            throw new IllegalArgumentException("Column not found: " + name +
                    ". Available: " + columns.stream().map(Column::qualifiedName).toList());
        }
        return idx;
    }

    public boolean hasColumn(String name) {
        return resolveIndex(name) != null;
    }

    private Integer resolveIndex(String name) {
        String lower = name.toLowerCase();
        Integer idx = nameIndex.get(lower);
        if (idx != null) return idx;

        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(name)) return i;
            if (columns.get(i).qualifiedName().equalsIgnoreCase(name)) return i;
        }
        return null;
    }

    public Schema withTablePrefix(String tableName) {
        List<Column> prefixed = columns.stream()
                .map(c -> c.withTable(tableName))
                .toList();
        return new Schema(prefixed);
    }

    public Schema merge(Schema other) {
        List<Column> merged = Stream.concat(columns.stream(), other.columns.stream()).toList();
        return new Schema(merged);
    }

    public Schema project(List<String> columnNames) {
        List<Column> projected = columnNames.stream()
                .map(this::getColumn)
                .toList();
        return new Schema(projected);
    }

    public Schema project(int... indices) {
        List<Column> projected = new ArrayList<>();
        for (int idx : indices) {
            projected.add(columns.get(idx));
        }
        return new Schema(projected);
    }

    @Override
    public String toString() {
        return columns.stream()
                .map(Column::toString)
                .collect(Collectors.joining(", ", "(", ")"));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Schema s && columns.equals(s.columns);
    }

    @Override
    public int hashCode() {
        return columns.hashCode();
    }
}
