package com.cloudqueryx.execution;

import com.cloudqueryx.common.Row;
import com.cloudqueryx.common.Schema;

public interface Operator extends AutoCloseable {

    void open();

    Row next();

    Schema getOutputSchema();

    @Override
    void close();

    default long getRowsProduced() {
        return -1;
    }
}
