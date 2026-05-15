package com.cloudqueryx.execution.operators;

import com.cloudqueryx.common.*;
import com.cloudqueryx.execution.Operator;
import com.cloudqueryx.expression.AggregateExpression;
import com.cloudqueryx.expression.Expression;

import java.util.*;

public class AggregateOperator implements Operator {

    private final Operator input;
    private final List<Expression> groupByKeys;
    private final List<AggregateExpression> aggregates;
    private final List<String> outputNames;
    private Schema outputSchema;
    private Iterator<Row> resultIterator;

    public AggregateOperator(Operator input, List<Expression> groupByKeys,
                             List<AggregateExpression> aggregates, List<String> outputNames) {
        this.input = input;
        this.groupByKeys = groupByKeys;
        this.aggregates = aggregates;
        this.outputNames = outputNames;
    }

    @Override
    public void open() {
        input.open();

        List<Column> columns = new ArrayList<>();
        Schema inputSchema = input.getOutputSchema();
        for (int i = 0; i < groupByKeys.size(); i++) {
            String name = outputNames != null && i < outputNames.size() ? outputNames.get(i) : "group" + i;
            columns.add(new Column(name, groupByKeys.get(i).getType(inputSchema)));
        }
        for (int i = 0; i < aggregates.size(); i++) {
            int idx = groupByKeys.size() + i;
            String name = outputNames != null && idx < outputNames.size() ? outputNames.get(idx) : aggregates.get(i).toString();
            columns.add(new Column(name, aggregates.get(i).getType(inputSchema)));
        }
        outputSchema = new Schema(columns);

        Map<GroupKey, List<Accumulator>> groups = new LinkedHashMap<>();
        Row row;
        while ((row = input.next()) != null) {
            GroupKey key = computeGroupKey(row, inputSchema);
            List<Accumulator> accums = groups.computeIfAbsent(key, k -> createAccumulators());
            for (int i = 0; i < aggregates.size(); i++) {
                AggregateExpression agg = aggregates.get(i);
                SqlValue value = agg.argument() != null
                        ? agg.argument().evaluate(row, inputSchema) : null;
                accums.get(i).accumulate(value);
            }
        }

        if (groups.isEmpty() && groupByKeys.isEmpty()) {
            List<Accumulator> accums = createAccumulators();
            groups.put(new GroupKey(new SqlValue[0]), accums);
        }

        List<Row> results = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            SqlValue[] values = new SqlValue[groupByKeys.size() + aggregates.size()];
            System.arraycopy(entry.getKey().values, 0, values, 0, groupByKeys.size());
            List<Accumulator> accums = entry.getValue();
            for (int i = 0; i < aggregates.size(); i++) {
                values[groupByKeys.size() + i] = accums.get(i).result();
            }
            results.add(new Row(values));
        }
        resultIterator = results.iterator();
    }

    private GroupKey computeGroupKey(Row row, Schema schema) {
        SqlValue[] keyValues = new SqlValue[groupByKeys.size()];
        for (int i = 0; i < groupByKeys.size(); i++) {
            keyValues[i] = groupByKeys.get(i).evaluate(row, schema);
        }
        return new GroupKey(keyValues);
    }

    private List<Accumulator> createAccumulators() {
        List<Accumulator> accums = new ArrayList<>();
        for (AggregateExpression agg : aggregates) {
            accums.add(new Accumulator(agg.function()));
        }
        return accums;
    }

    @Override
    public Row next() {
        return resultIterator.hasNext() ? resultIterator.next() : null;
    }

    @Override
    public Schema getOutputSchema() {
        return outputSchema;
    }

    @Override
    public void close() {
        input.close();
        resultIterator = null;
    }

    private record GroupKey(SqlValue[] values) {
        @Override
        public boolean equals(Object o) {
            return o instanceof GroupKey gk && Arrays.equals(values, gk.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }
    }

    private static class Accumulator {
        private final AggregateExpression.Function function;
        private long count;
        private double sum;
        private SqlValue min;
        private SqlValue max;

        Accumulator(AggregateExpression.Function function) {
            this.function = function;
            this.count = 0;
            this.sum = 0;
        }

        void accumulate(SqlValue value) {
            if (function == AggregateExpression.Function.COUNT) {
                if (value == null || !value.isNull()) count++;
                return;
            }
            if (value == null || value.isNull()) return;

            count++;
            switch (function) {
                case SUM, AVG -> sum += value.asDouble();
                case MIN -> { if (min == null || value.compareTo(min) < 0) min = value; }
                case MAX -> { if (max == null || value.compareTo(max) > 0) max = value; }
                default -> {}
            }
        }

        SqlValue result() {
            return switch (function) {
                case COUNT -> SqlValue.ofLong(count);
                case SUM -> count == 0 ? SqlValue.ofNull() : SqlValue.ofDouble(sum);
                case AVG -> count == 0 ? SqlValue.ofNull() : SqlValue.ofDouble(sum / count);
                case MIN -> min != null ? min : SqlValue.ofNull();
                case MAX -> max != null ? max : SqlValue.ofNull();
            };
        }
    }
}
