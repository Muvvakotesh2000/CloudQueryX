package com.cloudqueryx.common;

import java.util.Arrays;
import java.util.List;

public final class Row {

    private final SqlValue[] values;

    public Row(SqlValue[] values) {
        this.values = values;
    }

    public Row(List<SqlValue> values) {
        this.values = values.toArray(new SqlValue[0]);
    }

    public static Row of(SqlValue... values) {
        return new Row(values);
    }

    public SqlValue getValue(int index) {
        return values[index];
    }

    public int getColumnCount() {
        return values.length;
    }

    public SqlValue[] getValues() {
        return values.clone();
    }

    public Row project(int... indices) {
        SqlValue[] projected = new SqlValue[indices.length];
        for (int i = 0; i < indices.length; i++) {
            projected[i] = values[indices[i]];
        }
        return new Row(projected);
    }

    public Row merge(Row other) {
        SqlValue[] merged = new SqlValue[values.length + other.values.length];
        System.arraycopy(values, 0, merged, 0, values.length);
        System.arraycopy(other.values, 0, merged, values.length, other.values.length);
        return new Row(merged);
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Row r && Arrays.equals(values, r.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}
