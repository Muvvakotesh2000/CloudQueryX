package com.cloudqueryx.common;

public class CloudQueryXException extends RuntimeException {

    private final ErrorCode errorCode;

    public CloudQueryXException(ErrorCode errorCode, String message) {
        super(errorCode.name() + ": " + message);
        this.errorCode = errorCode;
    }

    public CloudQueryXException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode.name() + ": " + message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public enum ErrorCode {
        PARSE_ERROR,
        SEMANTIC_ERROR,
        TYPE_MISMATCH,
        COLUMN_NOT_FOUND,
        TABLE_NOT_FOUND,
        AMBIGUOUS_COLUMN,
        OPTIMIZER_ERROR,
        EXECUTION_ERROR,
        NETWORK_ERROR,
        STORAGE_ERROR,
        CONFIGURATION_ERROR,
        INTERNAL_ERROR,
        QUERY_CANCELLED,
        RESOURCE_EXHAUSTED
    }

    public static CloudQueryXException parseError(String message) {
        return new CloudQueryXException(ErrorCode.PARSE_ERROR, message);
    }

    public static CloudQueryXException tableNotFound(String tableName) {
        return new CloudQueryXException(ErrorCode.TABLE_NOT_FOUND, "Table not found: " + tableName);
    }

    public static CloudQueryXException columnNotFound(String columnName) {
        return new CloudQueryXException(ErrorCode.COLUMN_NOT_FOUND, "Column not found: " + columnName);
    }

    public static CloudQueryXException typeMismatch(String message) {
        return new CloudQueryXException(ErrorCode.TYPE_MISMATCH, message);
    }

    public static CloudQueryXException executionError(String message) {
        return new CloudQueryXException(ErrorCode.EXECUTION_ERROR, message);
    }

    public static CloudQueryXException executionError(String message, Throwable cause) {
        return new CloudQueryXException(ErrorCode.EXECUTION_ERROR, message, cause);
    }

    public static CloudQueryXException internal(String message) {
        return new CloudQueryXException(ErrorCode.INTERNAL_ERROR, message);
    }
}
