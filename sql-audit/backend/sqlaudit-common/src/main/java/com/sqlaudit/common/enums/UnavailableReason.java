package com.sqlaudit.common.enums;

/**
 * 回滚不可用的原因（设计文档 §5.2 / §5.4 / §5.6 / §5.7）。
 * <p>对应 {@code rollback_sql.unavailable_reason}，用于提交预览时显式告知用户「为什么回滚不了」。</p>
 */
public enum UnavailableReason {
    /** 目标表无主键（硬规则拒绝） */
    NO_PRIMARY_KEY,
    /** 批量 INSERT / INSERT...SELECT / ON DUPLICATE / REPLACE */
    BATCH_INSERT,
    /** 函数/表达式 SET、触发器级联、外键 CASCADE 导致前镜像失效 */
    NONDETERMINISTIC,
    /** 多表 JOIN UPDATE/DELETE */
    MULTI_TABLE_DML,
    /** 带 LIMIT / ORDER BY 的 DML（回滚不可靠） */
    LIMIT_ORDER_DML,
    /** 破坏性 DDL（DROP/TRUNCATE/DROP COLUMN/MODIFY），走影子表/快照，不生成 SQL */
    DESTRUCTIVE_DDL,
    /** IoTDB 不承诺自动回滚，走快照恢复 SOP */
    IOTDB_UNSUPPORTED,
    /** 解析失败，无法判定回滚能力 */
    PARSE_FAILED,
    /** 前镜像超过上限（5000 行 / 16MB），转 binlog / 快照 */
    PREIMAGE_TOO_LARGE,
    /** 生成的反向 SQL 语法自检失败 */
    SYNTAX_CHECK_FAILED
}
