package com.sqlaudit.common.enums;

/**
 * 危险子句 / 形态标识（README §4.3 只读判定的危险子句表）。
 *
 * <p>只读判定必须走 AST 白名单，任一命中即 not readOnly，强制走审核工单。</p>
 *
 * <p>README §4.3 表的每一行对应一个枚举值，验收要求每一行都有一条用例。</p>
 */
public enum DangerousClause {
    /** EXPLAIN ANALYZE：PG 会真实执行 DML；MySQL 8.0.18+ 也会真实执行 */
    EXPLAIN_ANALYZE,
    /** SELECT ... FOR UPDATE / FOR SHARE / LOCK IN SHARE MODE（加行锁） */
    FOR_UPDATE_LOCK,
    /** SELECT ... INTO OUTFILE / INTO DUMPFILE / INTO @var（写文件 / 会话状态） */
    INTO_OUTFILE,
    /** PG SELECT ... INTO new_table */
    SELECT_INTO,
    /** 数据修改 CTE：WITH ... DELETE/UPDATE/INSERT ... RETURNING */
    DATA_MODIFYING_CTE,
    /** 调用存储函数 / 存储过程 / 触发器副作用 */
    FUNCTION_SIDE_EFFECT,
    /** SET / SHOW 之外的会话变更、CALL、LOCK TABLES */
    SESSION_MUTATION,
    /** 解析置信度低于阈值，无法证明只读 */
    LOW_CONFIDENCE
}
