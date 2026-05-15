package com.cloudqueryx.common;

public record Column(String name, DataType type, boolean nullable, String tableName) {

    public Column(String name, DataType type) {
        this(name, type, true, null);
    }

    public Column(String name, DataType type, boolean nullable) {
        this(name, type, nullable, null);
    }

    public Column withTable(String table) {
        return new Column(name, type, nullable, table);
    }

    public String qualifiedName() {
        return tableName != null ? tableName + "." + name : name;
    }

    @Override
    public String toString() {
        return qualifiedName() + " " + type + (nullable ? "" : " NOT NULL");
    }
}
