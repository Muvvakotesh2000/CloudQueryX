package com.cloudqueryx.common;

public enum DataType {
    INTEGER(4, true),
    BIGINT(8, true),
    DOUBLE(8, true),
    DECIMAL(16, true),
    VARCHAR(0, false),
    BOOLEAN(1, false),
    DATE(4, false),
    TIMESTAMP(8, false),
    NULL(0, false);

    private final int fixedSize;
    private final boolean numeric;

    DataType(int fixedSize, boolean numeric) {
        this.fixedSize = fixedSize;
        this.numeric = numeric;
    }

    public int getFixedSize() {
        return fixedSize;
    }

    public boolean isNumeric() {
        return numeric;
    }

    public boolean isComparableTo(DataType other) {
        if (this == other || this == NULL || other == NULL) return true;
        if (this.numeric && other.numeric) return true;
        return false;
    }

    public static DataType promote(DataType left, DataType right) {
        if (left == right) return left;
        if (left == NULL) return right;
        if (right == NULL) return left;
        if (left == DOUBLE || right == DOUBLE) return DOUBLE;
        if (left == DECIMAL || right == DECIMAL) return DECIMAL;
        if (left == BIGINT || right == BIGINT) return BIGINT;
        if (left.numeric && right.numeric) return INTEGER;
        return VARCHAR;
    }

    public static DataType fromSqlName(String name) {
        return switch (name.toUpperCase()) {
            case "INT", "INTEGER" -> INTEGER;
            case "BIGINT" -> BIGINT;
            case "DOUBLE", "FLOAT", "REAL" -> DOUBLE;
            case "DECIMAL", "NUMERIC" -> DECIMAL;
            case "VARCHAR", "TEXT", "STRING", "CHAR" -> VARCHAR;
            case "BOOLEAN", "BOOL" -> BOOLEAN;
            case "DATE" -> DATE;
            case "TIMESTAMP" -> TIMESTAMP;
            default -> throw new IllegalArgumentException("Unknown SQL type: " + name);
        };
    }
}
