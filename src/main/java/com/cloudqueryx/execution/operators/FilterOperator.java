package com.cloudqueryx.execution.operators;

import com.cloudqueryx.common.Row;
import com.cloudqueryx.common.Schema;
import com.cloudqueryx.common.SqlValue;
import com.cloudqueryx.execution.Operator;
import com.cloudqueryx.expression.Expression;

public class FilterOperator implements Operator {

    private final Operator input;
    private final Expression condition;
    private long rowsProduced;

    public FilterOperator(Operator input, Expression condition) {
        this.input = input;
        this.condition = condition;
    }

    @Override
    public void open() {
        input.open();
        rowsProduced = 0;
    }

    @Override
    public Row next() {
        Row row;
        while ((row = input.next()) != null) {
            SqlValue result = condition.evaluate(row, input.getOutputSchema());
            if (!result.isNull() && result.asBoolean()) {
                rowsProduced++;
                return row;
            }
        }
        return null;
    }

    @Override
    public Schema getOutputSchema() {
        return input.getOutputSchema();
    }

    @Override
    public void close() {
        input.close();
    }

    @Override
    public long getRowsProduced() {
        return rowsProduced;
    }
}
