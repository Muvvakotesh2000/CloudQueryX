package com.cloudqueryx.parser;

import com.cloudqueryx.catalog.Catalog;
import com.cloudqueryx.common.CloudQueryXException;
import com.cloudqueryx.planner.logical.LogicalPlan;
import org.antlr.v4.runtime.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SqlParser {

    private static final Logger log = LoggerFactory.getLogger(SqlParser.class);
    private final Catalog catalog;

    public SqlParser(Catalog catalog) {
        this.catalog = catalog;
    }

    public LogicalPlan parse(String sql) {
        log.debug("Parsing SQL: {}", sql);

        CharStream input = CharStreams.fromString(sql);
        CloudQuerySQLLexer lexer = new CloudQuerySQLLexer(input);
        lexer.removeErrorListeners();
        lexer.addErrorListener(ThrowingErrorListener.INSTANCE);

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        CloudQuerySQLParser parser = new CloudQuerySQLParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(ThrowingErrorListener.INSTANCE);

        CloudQuerySQLParser.StatementsContext tree = parser.statements();

        AstBuilder astBuilder = new AstBuilder(catalog);
        LogicalPlan plan = astBuilder.visitStatements(tree);

        log.debug("Parsed logical plan:\n{}", plan.explain());
        return plan;
    }

    public LogicalPlan parseSelect(String sql) {
        CharStream input = CharStreams.fromString(sql);
        CloudQuerySQLLexer lexer = new CloudQuerySQLLexer(input);
        lexer.removeErrorListeners();
        lexer.addErrorListener(ThrowingErrorListener.INSTANCE);

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        CloudQuerySQLParser parser = new CloudQuerySQLParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(ThrowingErrorListener.INSTANCE);

        CloudQuerySQLParser.SelectStatementContext tree = parser.selectStatement();
        AstBuilder astBuilder = new AstBuilder(catalog);
        return astBuilder.visitSelectStatement(tree);
    }

    private static class ThrowingErrorListener extends BaseErrorListener {
        static final ThrowingErrorListener INSTANCE = new ThrowingErrorListener();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg, RecognitionException e) {
            throw CloudQueryXException.parseError(
                    "Syntax error at line " + line + ":" + charPositionInLine + " - " + msg);
        }
    }
}
