package com.cloudqueryx.expression;

public interface ExpressionVisitor<R> {
    R visitColumnRef(ColumnRef expr);
    R visitLiteral(Literal expr);
    R visitArithmetic(ArithmeticExpression expr);
    R visitComparison(ComparisonExpression expr);
    R visitLogical(LogicalExpression expr);
    R visitNot(NotExpression expr);
    R visitAggregate(AggregateExpression expr);
    R visitFunctionCall(FunctionCallExpression expr);
    R visitCase(CaseExpression expr);
    R visitBetween(BetweenExpression expr);
    R visitIn(InExpression expr);
    R visitLike(LikeExpression expr);
    R visitIsNull(IsNullExpression expr);
    R visitCast(CastExpression expr);
    R visitNegate(NegateExpression expr);
}
