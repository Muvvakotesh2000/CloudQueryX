package com.cloudqueryx.catalog;

import com.cloudqueryx.common.SqlValue;

import java.util.HashMap;
import java.util.Map;

public class TableStatistics {

    private long rowCount;
    private long sizeInBytes;
    private final Map<String, ColumnStatistics> columnStats;

    public TableStatistics(long rowCount, long sizeInBytes) {
        this.rowCount = rowCount;
        this.sizeInBytes = sizeInBytes;
        this.columnStats = new HashMap<>();
    }

    public static TableStatistics empty() {
        return new TableStatistics(0, 0);
    }

    public long getRowCount() { return rowCount; }
    public void setRowCount(long rowCount) { this.rowCount = rowCount; }

    public long getSizeInBytes() { return sizeInBytes; }
    public void setSizeInBytes(long sizeInBytes) { this.sizeInBytes = sizeInBytes; }

    public void setColumnStatistics(String column, ColumnStatistics stats) {
        columnStats.put(column.toLowerCase(), stats);
    }

    public ColumnStatistics getColumnStatistics(String column) {
        return columnStats.getOrDefault(column.toLowerCase(), ColumnStatistics.unknown());
    }

    public double getAverageRowSize() {
        return rowCount > 0 ? (double) sizeInBytes / rowCount : 100.0;
    }

    public static class ColumnStatistics {
        private final long distinctCount;
        private final long nullCount;
        private final SqlValue minValue;
        private final SqlValue maxValue;
        private final double averageSize;

        public ColumnStatistics(long distinctCount, long nullCount,
                                SqlValue minValue, SqlValue maxValue, double averageSize) {
            this.distinctCount = distinctCount;
            this.nullCount = nullCount;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.averageSize = averageSize;
        }

        public static ColumnStatistics unknown() {
            return new ColumnStatistics(-1, -1, null, null, 8.0);
        }

        public static ColumnStatistics of(long distinctCount, long nullCount) {
            return new ColumnStatistics(distinctCount, nullCount, null, null, 8.0);
        }

        public long getDistinctCount() { return distinctCount; }
        public long getNullCount() { return nullCount; }
        public SqlValue getMinValue() { return minValue; }
        public SqlValue getMaxValue() { return maxValue; }
        public double getAverageSize() { return averageSize; }

        public double getSelectivity(long totalRows) {
            if (distinctCount <= 0 || totalRows <= 0) return 0.1;
            return 1.0 / distinctCount;
        }
    }
}
