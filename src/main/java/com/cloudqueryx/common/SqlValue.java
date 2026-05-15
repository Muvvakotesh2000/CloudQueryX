package com.cloudqueryx.common;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

public abstract sealed class SqlValue implements Comparable<SqlValue>
        permits SqlValue.IntegerVal, SqlValue.LongVal, SqlValue.DoubleVal,
                SqlValue.DecimalVal, SqlValue.StringVal, SqlValue.BooleanVal,
                SqlValue.DateVal, SqlValue.TimestampVal, SqlValue.NullVal {

    public abstract DataType getType();
    public abstract Object getRawValue();

    public boolean isNull() {
        return this instanceof NullVal;
    }

    public int asInt() {
        if (this instanceof IntegerVal v) return v.value;
        if (this instanceof LongVal v) return (int) v.value;
        if (this instanceof DoubleVal v) return (int) v.value;
        if (this instanceof DecimalVal v) return v.value.intValue();
        if (this instanceof StringVal v) return Integer.parseInt(v.value);
        if (this instanceof BooleanVal v) return v.value ? 1 : 0;
        throw new ArithmeticException("Cannot convert " + getType() + " to int");
    }

    public long asLong() {
        if (this instanceof IntegerVal v) return v.value;
        if (this instanceof LongVal v) return v.value;
        if (this instanceof DoubleVal v) return (long) v.value;
        if (this instanceof DecimalVal v) return v.value.longValue();
        if (this instanceof StringVal v) return Long.parseLong(v.value);
        throw new ArithmeticException("Cannot convert " + getType() + " to long");
    }

    public double asDouble() {
        if (this instanceof IntegerVal v) return v.value;
        if (this instanceof LongVal v) return (double) v.value;
        if (this instanceof DoubleVal v) return v.value;
        if (this instanceof DecimalVal v) return v.value.doubleValue();
        if (this instanceof StringVal v) return Double.parseDouble(v.value);
        throw new ArithmeticException("Cannot convert " + getType() + " to double");
    }

    public String asString() {
        if (isNull()) return "NULL";
        return String.valueOf(getRawValue());
    }

    public boolean asBoolean() {
        if (this instanceof BooleanVal v) return v.value;
        if (this instanceof IntegerVal v) return v.value != 0;
        if (this instanceof StringVal v) return Boolean.parseBoolean(v.value);
        if (this instanceof NullVal) return false;
        throw new ArithmeticException("Cannot convert " + getType() + " to boolean");
    }

    public SqlValue add(SqlValue other) {
        if (isNull() || other.isNull()) return NullVal.INSTANCE;
        DataType promoted = DataType.promote(getType(), other.getType());
        return switch (promoted) {
            case INTEGER -> ofInt(asInt() + other.asInt());
            case BIGINT -> ofLong(asLong() + other.asLong());
            case DOUBLE -> ofDouble(asDouble() + other.asDouble());
            default -> throw new ArithmeticException("Cannot add " + getType() + " and " + other.getType());
        };
    }

    public SqlValue subtract(SqlValue other) {
        if (isNull() || other.isNull()) return NullVal.INSTANCE;
        DataType promoted = DataType.promote(getType(), other.getType());
        return switch (promoted) {
            case INTEGER -> ofInt(asInt() - other.asInt());
            case BIGINT -> ofLong(asLong() - other.asLong());
            case DOUBLE -> ofDouble(asDouble() - other.asDouble());
            default -> throw new ArithmeticException("Cannot subtract " + getType() + " and " + other.getType());
        };
    }

    public SqlValue multiply(SqlValue other) {
        if (isNull() || other.isNull()) return NullVal.INSTANCE;
        DataType promoted = DataType.promote(getType(), other.getType());
        return switch (promoted) {
            case INTEGER -> ofInt(asInt() * other.asInt());
            case BIGINT -> ofLong(asLong() * other.asLong());
            case DOUBLE -> ofDouble(asDouble() * other.asDouble());
            default -> throw new ArithmeticException("Cannot multiply " + getType() + " and " + other.getType());
        };
    }

    public SqlValue divide(SqlValue other) {
        if (isNull() || other.isNull()) return NullVal.INSTANCE;
        if (other.asDouble() == 0.0) throw new ArithmeticException("Division by zero");
        DataType promoted = DataType.promote(getType(), other.getType());
        return switch (promoted) {
            case INTEGER -> ofInt(asInt() / other.asInt());
            case BIGINT -> ofLong(asLong() / other.asLong());
            case DOUBLE -> ofDouble(asDouble() / other.asDouble());
            default -> throw new ArithmeticException("Cannot divide " + getType() + " and " + other.getType());
        };
    }

    public SqlValue modulo(SqlValue other) {
        if (isNull() || other.isNull()) return NullVal.INSTANCE;
        return ofDouble(asDouble() % other.asDouble());
    }

    public SqlValue negate() {
        if (this instanceof IntegerVal v) return ofInt(-v.value);
        if (this instanceof LongVal v) return ofLong(-v.value);
        if (this instanceof DoubleVal v) return ofDouble(-v.value);
        if (this instanceof DecimalVal v) return ofDecimal(v.value.negate());
        if (this instanceof NullVal) return NullVal.INSTANCE;
        throw new ArithmeticException("Cannot negate " + getType());
    }

    @Override
    @SuppressWarnings("unchecked")
    public int compareTo(SqlValue other) {
        if (isNull() && other.isNull()) return 0;
        if (isNull()) return -1;
        if (other.isNull()) return 1;

        if (getType().isNumeric() && other.getType().isNumeric()) {
            return Double.compare(asDouble(), other.asDouble());
        }
        if (getType() == other.getType() && getRawValue() instanceof Comparable c) {
            return c.compareTo(other.getRawValue());
        }
        return asString().compareTo(other.asString());
    }

    // ─── Factory Methods ────────────────────────────────────────────────────

    public static SqlValue ofInt(int value) { return new IntegerVal(value); }
    public static SqlValue ofLong(long value) { return new LongVal(value); }
    public static SqlValue ofDouble(double value) { return new DoubleVal(value); }
    public static SqlValue ofDecimal(BigDecimal value) { return new DecimalVal(value); }
    public static SqlValue ofString(String value) { return new StringVal(value); }
    public static SqlValue ofBoolean(boolean value) { return new BooleanVal(value); }
    public static SqlValue ofDate(LocalDate value) { return new DateVal(value); }
    public static SqlValue ofTimestamp(LocalDateTime value) { return new TimestampVal(value); }
    public static SqlValue ofNull() { return NullVal.INSTANCE; }

    public static SqlValue fromObject(Object obj, DataType type) {
        if (obj == null) return ofNull();
        return switch (type) {
            case INTEGER -> ofInt(((Number) obj).intValue());
            case BIGINT -> ofLong(((Number) obj).longValue());
            case DOUBLE -> ofDouble(((Number) obj).doubleValue());
            case DECIMAL -> ofDecimal(obj instanceof BigDecimal bd ? bd : new BigDecimal(obj.toString()));
            case VARCHAR -> ofString(obj.toString());
            case BOOLEAN -> ofBoolean((Boolean) obj);
            case DATE -> ofDate((LocalDate) obj);
            case TIMESTAMP -> ofTimestamp((LocalDateTime) obj);
            case NULL -> ofNull();
        };
    }

    // ─── Value Types ────────────────────────────────────────────────────────

    public static final class IntegerVal extends SqlValue {
        private final int value;
        public IntegerVal(int value) { this.value = value; }
        @Override public DataType getType() { return DataType.INTEGER; }
        @Override public Object getRawValue() { return value; }
        @Override public String toString() { return String.valueOf(value); }
        @Override public boolean equals(Object o) { return o instanceof IntegerVal v && v.value == value; }
        @Override public int hashCode() { return Integer.hashCode(value); }
    }

    public static final class LongVal extends SqlValue {
        private final long value;
        public LongVal(long value) { this.value = value; }
        @Override public DataType getType() { return DataType.BIGINT; }
        @Override public Object getRawValue() { return value; }
        @Override public String toString() { return String.valueOf(value); }
        @Override public boolean equals(Object o) { return o instanceof LongVal v && v.value == value; }
        @Override public int hashCode() { return Long.hashCode(value); }
    }

    public static final class DoubleVal extends SqlValue {
        private final double value;
        public DoubleVal(double value) { this.value = value; }
        @Override public DataType getType() { return DataType.DOUBLE; }
        @Override public Object getRawValue() { return value; }
        @Override public String toString() { return String.valueOf(value); }
        @Override public boolean equals(Object o) { return o instanceof DoubleVal v && Double.compare(v.value, value) == 0; }
        @Override public int hashCode() { return Double.hashCode(value); }
    }

    public static final class DecimalVal extends SqlValue {
        private final BigDecimal value;
        public DecimalVal(BigDecimal value) { this.value = value; }
        @Override public DataType getType() { return DataType.DECIMAL; }
        @Override public Object getRawValue() { return value; }
        @Override public String toString() { return value.toPlainString(); }
        @Override public boolean equals(Object o) { return o instanceof DecimalVal v && v.value.compareTo(value) == 0; }
        @Override public int hashCode() { return value.stripTrailingZeros().hashCode(); }
    }

    public static final class StringVal extends SqlValue {
        private final String value;
        public StringVal(String value) { this.value = value; }
        @Override public DataType getType() { return DataType.VARCHAR; }
        @Override public Object getRawValue() { return value; }
        @Override public String toString() { return value; }
        @Override public boolean equals(Object o) { return o instanceof StringVal v && Objects.equals(v.value, value); }
        @Override public int hashCode() { return Objects.hashCode(value); }
    }

    public static final class BooleanVal extends SqlValue {
        private final boolean value;
        public BooleanVal(boolean value) { this.value = value; }
        @Override public DataType getType() { return DataType.BOOLEAN; }
        @Override public Object getRawValue() { return value; }
        @Override public String toString() { return String.valueOf(value); }
        @Override public boolean equals(Object o) { return o instanceof BooleanVal v && v.value == value; }
        @Override public int hashCode() { return Boolean.hashCode(value); }
    }

    public static final class DateVal extends SqlValue {
        private final LocalDate value;
        public DateVal(LocalDate value) { this.value = value; }
        @Override public DataType getType() { return DataType.DATE; }
        @Override public Object getRawValue() { return value; }
        @Override public String toString() { return value.toString(); }
        @Override public boolean equals(Object o) { return o instanceof DateVal v && Objects.equals(v.value, value); }
        @Override public int hashCode() { return Objects.hashCode(value); }
    }

    public static final class TimestampVal extends SqlValue {
        private final LocalDateTime value;
        public TimestampVal(LocalDateTime value) { this.value = value; }
        @Override public DataType getType() { return DataType.TIMESTAMP; }
        @Override public Object getRawValue() { return value; }
        @Override public String toString() { return value.toString(); }
        @Override public boolean equals(Object o) { return o instanceof TimestampVal v && Objects.equals(v.value, value); }
        @Override public int hashCode() { return Objects.hashCode(value); }
    }

    public static final class NullVal extends SqlValue {
        public static final NullVal INSTANCE = new NullVal();
        private NullVal() {}
        @Override public DataType getType() { return DataType.NULL; }
        @Override public Object getRawValue() { return null; }
        @Override public String toString() { return "NULL"; }
        @Override public boolean equals(Object o) { return o instanceof NullVal; }
        @Override public int hashCode() { return 0; }
    }
}
