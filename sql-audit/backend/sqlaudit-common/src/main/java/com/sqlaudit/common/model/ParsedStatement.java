package com.sqlaudit.common.model;

import com.sqlaudit.common.enums.DangerousClause;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.StatementType;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析器输出统一中间模型（README §4.3）。
 *
 * <p>三方言（MySQL/PG/IoTDB）解析后归一化到本模型，下游 rule/rollback/query 只依赖它。</p>
 *
 * <p>契约要点：</p>
 * <ul>
 *   <li>解析失败 → {@code parseConfidence=0}，绝不「降级放行」或假装解析成功（README §4.3 铁律）；</li>
 *   <li>{@code whereAst}/{@code limitAst} 是方言原生 AST 节点，由 parser 模块填充，
 *       common 层用 {@link Object} 占位以避免引入具体 AST 库依赖，下游使用时强转。</li>
 * </ul>
 */
public class ParsedStatement {

    private Dialect dialect;
    private StatementType statementType;
    private List<SqlObject> objects = new ArrayList<>();

    /** 只读判定结果：true 表示可走查询免审通道（README §4.3 AST 白名单） */
    private boolean isReadOnly;

    /** 命中的危险子句（README §4.3 表），空表示无危险子句 */
    private List<DangerousClause> dangerousClauses = new ArrayList<>();

    /** 方言原生 AST 节点（WHERE 子句），parser 填充，下游强转 */
    private Object whereAst;

    /** 方言原生 AST 节点（LIMIT 子句），parser 填充，下游强转 */
    private Object limitAst;

    /** 方言原生 AST 语句节点（如 SQLUpdateStatement），parser 填充，下游强转；
     *  rule 模块用它判定 LIMIT/ORDER BY、多表 JOIN、SET 表达式等 */
    private Object sourceStatement;

    private boolean containsSubquery;
    private boolean containsFunction;
    /** 语句是否触及主键列（影响回滚能力判定） */
    private boolean affectsPrimaryKey;

    private String originalSql;
    private String normalizedSql;

    /** 解析置信度 0~100；0 表示解析失败，必须人工审核 */
    private int parseConfidence;

    public Dialect getDialect() {
        return dialect;
    }

    public void setDialect(Dialect dialect) {
        this.dialect = dialect;
    }

    public StatementType getStatementType() {
        return statementType;
    }

    public void setStatementType(StatementType statementType) {
        this.statementType = statementType;
    }

    public List<SqlObject> getObjects() {
        return objects;
    }

    public void setObjects(List<SqlObject> objects) {
        this.objects = objects;
    }

    public boolean isReadOnly() {
        return isReadOnly;
    }

    public void setReadOnly(boolean readOnly) {
        isReadOnly = readOnly;
    }

    public List<DangerousClause> getDangerousClauses() {
        return dangerousClauses;
    }

    public void setDangerousClauses(List<DangerousClause> dangerousClauses) {
        this.dangerousClauses = dangerousClauses;
    }

    public Object getWhereAst() {
        return whereAst;
    }

    public void setWhereAst(Object whereAst) {
        this.whereAst = whereAst;
    }

    public Object getLimitAst() {
        return limitAst;
    }

    public void setLimitAst(Object limitAst) {
        this.limitAst = limitAst;
    }

    public Object getSourceStatement() {
        return sourceStatement;
    }

    public void setSourceStatement(Object sourceStatement) {
        this.sourceStatement = sourceStatement;
    }

    public boolean isContainsSubquery() {
        return containsSubquery;
    }

    public void setContainsSubquery(boolean containsSubquery) {
        this.containsSubquery = containsSubquery;
    }

    public boolean isContainsFunction() {
        return containsFunction;
    }

    public void setContainsFunction(boolean containsFunction) {
        this.containsFunction = containsFunction;
    }

    public boolean isAffectsPrimaryKey() {
        return affectsPrimaryKey;
    }

    public void setAffectsPrimaryKey(boolean affectsPrimaryKey) {
        this.affectsPrimaryKey = affectsPrimaryKey;
    }

    public String getOriginalSql() {
        return originalSql;
    }

    public void setOriginalSql(String originalSql) {
        this.originalSql = originalSql;
    }

    public String getNormalizedSql() {
        return normalizedSql;
    }

    public void setNormalizedSql(String normalizedSql) {
        this.normalizedSql = normalizedSql;
    }

    public int getParseConfidence() {
        return parseConfidence;
    }

    public void setParseConfidence(int parseConfidence) {
        this.parseConfidence = parseConfidence;
    }
}
