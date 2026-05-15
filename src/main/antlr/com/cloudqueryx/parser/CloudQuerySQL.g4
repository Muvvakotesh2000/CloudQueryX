grammar CloudQuerySQL;

@header {
package com.cloudqueryx.parser;
}

// ─── Top-level ──────────────────────────────────────────────────────────────────

statements
    : statement ( ';' statement )* ';'? EOF
    ;

statement
    : selectStatement
    | createTableStatement
    | dropTableStatement
    | insertStatement
    | explainStatement
    ;

// ─── SELECT ─────────────────────────────────────────────────────────────────────

selectStatement
    : withClause? queryExpression orderByClause? limitClause?
    ;

withClause
    : WITH cte ( ',' cte )*
    ;

cte
    : identifier AS '(' selectStatement ')'
    ;

queryExpression
    : queryTerm ( ( UNION | INTERSECT | EXCEPT ) ALL? queryTerm )*
    ;

queryTerm
    : SELECT setQuantifier? selectItem ( ',' selectItem )*
      ( FROM tableReference ( ',' tableReference )* )?
      whereClause?
      groupByClause?
      havingClause?
    ;

setQuantifier
    : DISTINCT
    | ALL
    ;

selectItem
    : expression ( AS? columnAlias )?          # selectExpression
    | qualifiedName '.' '*'                     # selectAllFromTable
    | '*'                                       # selectAll
    ;

columnAlias
    : identifier
    | STRING
    ;

// ─── FROM / JOIN ────────────────────────────────────────────────────────────────

tableReference
    : tablePrimary joinClause*
    ;

tablePrimary
    : qualifiedName ( AS? identifier )?                         # tableSource
    | '(' selectStatement ')' ( AS? identifier )?               # subquerySource
    | LATERAL '(' selectStatement ')' ( AS? identifier )?       # lateralSource
    ;

joinClause
    : joinType JOIN tablePrimary joinCriteria                   # qualifiedJoin
    | CROSS JOIN tablePrimary                                   # crossJoin
    | NATURAL joinType JOIN tablePrimary                        # naturalJoin
    ;

joinType
    : INNER?
    | LEFT OUTER?
    | RIGHT OUTER?
    | FULL OUTER?
    ;

joinCriteria
    : ON expression
    | USING '(' identifier ( ',' identifier )* ')'
    ;

// ─── Clauses ────────────────────────────────────────────────────────────────────

whereClause
    : WHERE expression
    ;

groupByClause
    : GROUP BY expression ( ',' expression )*
    ;

havingClause
    : HAVING expression
    ;

orderByClause
    : ORDER BY sortItem ( ',' sortItem )*
    ;

sortItem
    : expression ordering=( ASC | DESC )? ( NULLS nullOrdering=( FIRST | LAST ) )?
    ;

limitClause
    : LIMIT limit=expression ( OFFSET offset=expression )?
    | OFFSET offset=expression ( LIMIT limit=expression )?
    ;

// ─── DDL ────────────────────────────────────────────────────────────────────────

createTableStatement
    : CREATE TABLE ( IF NOT EXISTS )? qualifiedName
      '(' columnDefinition ( ',' columnDefinition )* ( ',' tableConstraint )* ')'
    ;

columnDefinition
    : identifier columnType columnConstraint*
    ;

columnType
    : INT                                                       # intType
    | INTEGER                                                   # integerType
    | BIGINT                                                    # bigintType
    | DOUBLE PRECISION?                                         # doubleType
    | DECIMAL ( '(' precision=NUMBER ( ',' scale=NUMBER )? ')' )? # decimalType
    | VARCHAR ( '(' length=NUMBER ')' )?                        # varcharType
    | BOOLEAN                                                   # booleanType
    | DATE                                                      # dateType
    | TIMESTAMP                                                 # timestampType
    ;

columnConstraint
    : NOT NULL                                                  # notNullConstraint
    | PRIMARY KEY                                               # primaryKeyColumnConstraint
    | DEFAULT expression                                        # defaultConstraint
    ;

tableConstraint
    : PRIMARY KEY '(' identifier ( ',' identifier )* ')'       # primaryKeyTableConstraint
    ;

dropTableStatement
    : DROP TABLE ( IF EXISTS )? qualifiedName
    ;

// ─── DML ────────────────────────────────────────────────────────────────────────

insertStatement
    : INSERT INTO qualifiedName
      ( '(' identifier ( ',' identifier )* ')' )?
      ( VALUES valuesList ( ',' valuesList )* | selectStatement )
    ;

valuesList
    : '(' expression ( ',' expression )* ')'
    ;

// ─── EXPLAIN ────────────────────────────────────────────────────────────────────

explainStatement
    : EXPLAIN ( ANALYZE | VERBOSE )* statement
    ;

// ─── Expressions ────────────────────────────────────────────────────────────────

expression
    : primary                                                                       # primaryExpression
    | qualifiedName '(' DISTINCT? ( expression ( ',' expression )* )? ')'
      ( OVER windowSpec )?                                                          # functionExpression
    | qualifiedName '(' '*' ')'                                                     # countAllExpression
    | CAST '(' expression AS columnType ')'                                         # castExpression
    | op=( MINUS | PLUS ) expression                                                # unaryExpression
    | expression op=( STAR | SLASH | PERCENT ) expression                           # multiplicativeExpression
    | expression op=( PLUS | MINUS ) expression                                     # additiveExpression
    | expression CONCAT expression                                                  # concatenationExpression
    | expression comparisonOperator expression                                      # comparisonExpression
    | expression IS NOT? NULL                                                       # isNullExpression
    | expression NOT? BETWEEN expression AND expression                             # betweenExpression
    | expression NOT? IN '(' ( expression ( ',' expression )* | selectStatement ) ')'  # inExpression
    | expression NOT? LIKE expression ( ESCAPE expression )?                        # likeExpression
    | EXISTS '(' selectStatement ')'                                                # existsExpression
    | '(' selectStatement ')'                                                       # scalarSubqueryExpression
    | NOT expression                                                                # notExpression
    | expression AND expression                                                     # andExpression
    | expression OR expression                                                      # orExpression
    | CASE operand=expression?
      ( WHEN condition=expression THEN result=expression )+
      ( ELSE elseResult=expression )?
      END                                                                           # caseExpression
    ;

windowSpec
    : '(' ( PARTITION BY expression ( ',' expression )* )?
          ( ORDER BY sortItem ( ',' sortItem )* )?
      ')'
    ;

primary
    : NUMBER                                                    # numericLiteral
    | DECIMAL_NUMBER                                            # decimalLiteral
    | STRING                                                    # stringLiteral
    | TRUE                                                      # booleanTrueLiteral
    | FALSE                                                     # booleanFalseLiteral
    | NULL                                                      # nullLiteral
    | PARAMETER                                                 # parameterLiteral
    | identifier                                                # columnReference
    | qualifiedName                                             # qualifiedColumnReference
    | '(' expression ')'                                        # parenthesizedExpression
    ;

comparisonOperator
    : EQ | NEQ | NEQ2 | LT | GT | LTE | GTE
    ;

qualifiedName
    : identifier ('.' identifier)*
    ;

identifier
    : IDENTIFIER
    | QUOTED_IDENTIFIER
    | nonReservedKeyword
    ;

nonReservedKeyword
    : ALL | ANALYZE | AS | ASC | BY | CAST | CROSS | DATE | DEFAULT
    | DESC | DISTINCT | ESCAPE | EXISTS | EXPLAIN | FIRST | FULL
    | IF | INNER | KEY | LAST | LATERAL | LEFT | LIMIT | NATURAL | NULLS
    | OFFSET | ON | ORDER | OUTER | OVER | PARTITION | PRECISION
    | RIGHT | TIMESTAMP | UNION | USING | VERBOSE
    ;

// ─── Lexer Rules ────────────────────────────────────────────────────────────────

// Keywords
ALL         : A L L ;
ANALYZE     : A N A L Y Z E ;
AND         : A N D ;
AS          : A S ;
ASC         : A S C ;
BETWEEN     : B E T W E E N ;
BIGINT      : B I G I N T ;
BOOLEAN     : B O O L E A N ;
BY          : B Y ;
CASE        : C A S E ;
CAST        : C A S T ;
CREATE      : C R E A T E ;
CROSS       : C R O S S ;
DATE        : D A T E ;
DECIMAL     : D E C I M A L ;
DEFAULT     : D E F A U L T ;
DESC        : D E S C ;
DISTINCT    : D I S T I N C T ;
DOUBLE      : D O U B L E ;
DROP        : D R O P ;
ELSE        : E L S E ;
END         : E N D ;
ESCAPE      : E S C A P E ;
EXCEPT      : E X C E P T ;
EXISTS      : E X I S T S ;
EXPLAIN     : E X P L A I N ;
FALSE       : F A L S E ;
FIRST       : F I R S T ;
FROM        : F R O M ;
FULL        : F U L L ;
GROUP       : G R O U P ;
HAVING      : H A V I N G ;
IF          : I F ;
IN          : I N ;
INNER       : I N N E R ;
INSERT      : I N S E R T ;
INT         : I N T ;
INTEGER     : I N T E G E R ;
INTERSECT   : I N T E R S E C T ;
INTO        : I N T O ;
IS          : I S ;
JOIN        : J O I N ;
KEY         : K E Y ;
LAST        : L A S T ;
LATERAL     : L A T E R A L ;
LEFT        : L E F T ;
LIKE        : L I K E ;
LIMIT       : L I M I T ;
NATURAL     : N A T U R A L ;
NOT         : N O T ;
NULL        : N U L L ;
NULLS       : N U L L S ;
OFFSET      : O F F S E T ;
ON          : O N ;
OR          : O R ;
ORDER       : O R D E R ;
OUTER       : O U T E R ;
OVER        : O V E R ;
PARTITION   : P A R T I T I O N ;
PRECISION   : P R E C I S I O N ;
PRIMARY     : P R I M A R Y ;
RIGHT       : R I G H T ;
SELECT      : S E L E C T ;
TABLE       : T A B L E ;
THEN        : T H E N ;
TIMESTAMP   : T I M E S T A M P ;
TRUE        : T R U E ;
UNION       : U N I O N ;
USING       : U S I N G ;
VALUES      : V A L U E S ;
VARCHAR     : V A R C H A R ;
VERBOSE     : V E R B O S E ;
WHEN        : W H E N ;
WHERE       : W H E R E ;
WITH        : W I T H ;

// Operators
EQ          : '=' ;
NEQ         : '<>' ;
NEQ2        : '!=' ;
LT          : '<' ;
GT          : '>' ;
LTE         : '<=' ;
GTE         : '>=' ;
PLUS        : '+' ;
MINUS       : '-' ;
STAR        : '*' ;
SLASH       : '/' ;
PERCENT     : '%' ;
CONCAT      : '||' ;
PARAMETER   : '?' ;

// Literals
NUMBER
    : DIGIT+
    ;

DECIMAL_NUMBER
    : DIGIT+ '.' DIGIT*
    | '.' DIGIT+
    ;

STRING
    : '\'' ( ~'\'' | '\'\'' )* '\''
    ;

IDENTIFIER
    : LETTER ( LETTER | DIGIT | '_' )*
    ;

QUOTED_IDENTIFIER
    : '"' ( ~'"' | '""' )* '"'
    ;

// Whitespace / Comments
WS          : [ \t\r\n]+ -> skip ;
LINE_COMMENT: '--' ~[\r\n]* -> skip ;
BLOCK_COMMENT: '/*' .*? '*/' -> skip ;

// Fragments
fragment LETTER : [a-zA-Z_] ;
fragment DIGIT  : [0-9] ;
fragment A : [aA]; fragment B : [bB]; fragment C : [cC]; fragment D : [dD];
fragment E : [eE]; fragment F : [fF]; fragment G : [gG]; fragment H : [hH];
fragment I : [iI]; fragment J : [jJ]; fragment K : [kK]; fragment L : [lL];
fragment M : [mM]; fragment N : [nN]; fragment O : [oO]; fragment P : [pP];
fragment Q : [qQ]; fragment R : [rR]; fragment S : [sS]; fragment T : [tT];
fragment U : [uU]; fragment V : [vV]; fragment W : [wW]; fragment X : [xX];
fragment Y : [yY]; fragment Z : [zZ];
