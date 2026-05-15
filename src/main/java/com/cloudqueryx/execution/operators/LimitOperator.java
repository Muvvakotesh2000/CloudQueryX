package com.cloudqueryx.execution.operators;

import com.cloudqueryx.common.Row;
import com.cloudqueryx.common.Schema;
import com.cloudqueryx.execution.Operator;

public class LimitOperator implements Operator {

    private final Operator input;
    private final long limit;
    private final long offset;
    private long skipped;
    private long emitted;

    public LimitOperator(Operator input, long limit, long offset) {
        this.input = input;
        this.limit = limit;
        this.offset = offset;
    }

    @Override
    public void open() {
        input.open();
        skipped = 0;
        emitted = 0;
    }

    @Override
    public Row next() {
        while (skipped < offset) {
            Row row = input.next();
            if (row == null) return null;
            skipped++;
        }

        if (emitted >= limit) return null;

        Row row = input.next();
        if (row != null) emitted++;
        return row;
    }

    @Override
    public Schema getOutputSchema() {
        return input.getOutputSchema();
    }

    @Override
    public void close() {
        input.close();
    }
}
