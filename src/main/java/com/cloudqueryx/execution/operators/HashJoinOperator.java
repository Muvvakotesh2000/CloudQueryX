package com.cloudqueryx.execution.operators;

import com.cloudqueryx.common.*;
import com.cloudqueryx.execution.Operator;
import com.cloudqueryx.expression.*;
import com.cloudqueryx.planner.logical.Join;

import java.util.*;

public class HashJoinOperator implements Operator {

    private final Operator buildInput;
    private final Operator probeInput;
    private final Expression condition;
    private final Join.JoinType joinType;
    private Schema outputSchema;

    private Map<Long, List<Row>> hashTable;
    private Row currentProbeRow;
    private Iterator<Row> currentMatches;
    private boolean done;
    private long rowsProduced;

    private int[] buildKeyIndices;
    private int[] probeKeyIndices;
    private boolean useKeyHashing;

    public HashJoinOperator(Operator buildInput, Operator probeInput,
                            Expression condition, Join.JoinType joinType) {
        this.buildInput = buildInput;
        this.probeInput = probeInput;
        this.condition = condition;
        this.joinType = joinType;
    }

    @Override
    public void open() {
        buildInput.open();
        probeInput.open();
        outputSchema = buildInput.getOutputSchema().merge(probeInput.getOutputSchema());
        hashTable = new HashMap<>();
        done = false;
        rowsProduced = 0;

        extractKeyIndices();

        Row row;
        while ((row = buildInput.next()) != null) {
            long hash = computeBuildHash(row);
            hashTable.computeIfAbsent(hash, k -> new ArrayList<>()).add(row);
        }
        buildInput.close();

        currentProbeRow = null;
        currentMatches = Collections.emptyIterator();
    }

    private void extractKeyIndices() {
        useKeyHashing = false;
        if (condition instanceof ComparisonExpression comp && comp.op() == ComparisonExpression.Op.EQUAL) {
            int[] keys = resolveEqualityKeys(comp);
            if (keys != null) {
                buildKeyIndices = new int[]{keys[0]};
                probeKeyIndices = new int[]{keys[1]};
                useKeyHashing = true;
                return;
            }
        }

        if (condition instanceof LogicalExpression le && le.op() == LogicalExpression.Op.AND) {
            List<int[]> keyPairs = new ArrayList<>();
            collectEqualityKeys(condition, keyPairs);
            if (!keyPairs.isEmpty()) {
                buildKeyIndices = keyPairs.stream().mapToInt(k -> k[0]).toArray();
                probeKeyIndices = keyPairs.stream().mapToInt(k -> k[1]).toArray();
                useKeyHashing = true;
            }
        }
    }

    private void collectEqualityKeys(Expression expr, List<int[]> keyPairs) {
        if (expr instanceof ComparisonExpression comp && comp.op() == ComparisonExpression.Op.EQUAL) {
            int[] keys = resolveEqualityKeys(comp);
            if (keys != null) keyPairs.add(keys);
        } else if (expr instanceof LogicalExpression le && le.op() == LogicalExpression.Op.AND) {
            collectEqualityKeys(le.left(), keyPairs);
            collectEqualityKeys(le.right(), keyPairs);
        }
    }

    private int[] resolveEqualityKeys(ComparisonExpression comp) {
        if (comp.left() instanceof ColumnRef leftRef && comp.right() instanceof ColumnRef rightRef) {
            Schema buildSchema = buildInput.getOutputSchema();
            Schema probeSchema = probeInput.getOutputSchema();

            int buildIdx = tryResolve(leftRef, buildSchema);
            int probeIdx = tryResolve(rightRef, probeSchema);
            if (buildIdx >= 0 && probeIdx >= 0) return new int[]{buildIdx, probeIdx};

            buildIdx = tryResolve(rightRef, buildSchema);
            probeIdx = tryResolve(leftRef, probeSchema);
            if (buildIdx >= 0 && probeIdx >= 0) return new int[]{buildIdx, probeIdx};
        }
        return null;
    }

    private int tryResolve(ColumnRef ref, Schema schema) {
        try {
            return schema.getColumnIndex(ref.qualifiedName());
        } catch (IllegalArgumentException e) {
            try {
                return schema.getColumnIndex(ref.columnName());
            } catch (IllegalArgumentException e2) {
                return -1;
            }
        }
    }

    @Override
    public Row next() {
        while (!done) {
            while (currentMatches.hasNext()) {
                Row buildRow = currentMatches.next();
                Row merged = buildRow.merge(currentProbeRow);
                if (condition == null || evaluateCondition(merged)) {
                    rowsProduced++;
                    return merged;
                }
            }

            currentProbeRow = probeInput.next();
            if (currentProbeRow == null) {
                done = true;
                return null;
            }

            long hash = computeProbeHash(currentProbeRow);
            List<Row> candidates = hashTable.getOrDefault(hash, Collections.emptyList());

            if (candidates.isEmpty() && (joinType == Join.JoinType.LEFT || joinType == Join.JoinType.FULL)) {
                Row nullBuild = createNullRow(buildInput.getOutputSchema().getColumnCount());
                rowsProduced++;
                return nullBuild.merge(currentProbeRow);
            }

            currentMatches = candidates.iterator();
        }
        return null;
    }

    private boolean evaluateCondition(Row merged) {
        SqlValue result = condition.evaluate(merged, outputSchema);
        return !result.isNull() && result.asBoolean();
    }

    private long computeBuildHash(Row row) {
        if (!useKeyHashing) return 0;
        long hash = 1;
        for (int idx : buildKeyIndices) {
            SqlValue val = row.getValue(idx);
            hash = hash * 31 + (val.isNull() ? 0 : val.hashCode());
        }
        return hash;
    }

    private long computeProbeHash(Row row) {
        if (!useKeyHashing) return 0;
        long hash = 1;
        for (int idx : probeKeyIndices) {
            SqlValue val = row.getValue(idx);
            hash = hash * 31 + (val.isNull() ? 0 : val.hashCode());
        }
        return hash;
    }

    private Row createNullRow(int columnCount) {
        SqlValue[] values = new SqlValue[columnCount];
        Arrays.fill(values, SqlValue.ofNull());
        return new Row(values);
    }

    @Override
    public Schema getOutputSchema() {
        return outputSchema;
    }

    @Override
    public void close() {
        probeInput.close();
        hashTable = null;
    }

    @Override
    public long getRowsProduced() {
        return rowsProduced;
    }
}
