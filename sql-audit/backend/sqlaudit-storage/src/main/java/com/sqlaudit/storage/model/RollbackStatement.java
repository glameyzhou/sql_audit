package com.sqlaudit.storage.model;

import java.time.LocalDateTime;

/**
 * 反向语句明细实体（对应 rollback_statement 表，设计文档 §5.9）。
 * <p>每行一条反向语句，回放时分批流式读取 + 逐条幂等（applied_at IS NULL 条件更新）。</p>
 * <p>唯一写入方：sqlaudit-rollback。</p>
 */
public class RollbackStatement {

    private Long id;
    private Long rollbackId;
    /** 回放顺序（与原行顺序无关，但必须稳定） */
    private Integer stmtOrder;
    /** 加密；带 ? 占位符的参数化语句 */
    private byte[] reverseSql;
    /** 加密；该条语句的旧值参数（CBOR/JSON） */
    private byte[] valueParams;
    /** 逐条幂等回放时间戳 */
    private LocalDateTime appliedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRollbackId() {
        return rollbackId;
    }

    public void setRollbackId(Long rollbackId) {
        this.rollbackId = rollbackId;
    }

    public Integer getStmtOrder() {
        return stmtOrder;
    }

    public void setStmtOrder(Integer stmtOrder) {
        this.stmtOrder = stmtOrder;
    }

    public byte[] getReverseSql() {
        return reverseSql;
    }

    public void setReverseSql(byte[] reverseSql) {
        this.reverseSql = reverseSql;
    }

    public byte[] getValueParams() {
        return valueParams;
    }

    public void setValueParams(byte[] valueParams) {
        this.valueParams = valueParams;
    }

    public LocalDateTime getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(LocalDateTime appliedAt) {
        this.appliedAt = appliedAt;
    }
}
