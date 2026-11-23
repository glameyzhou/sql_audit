package com.sqlaudit.storage.model;

import java.time.LocalDateTime;

/**
 * 反向语句明细实体（对应 t_rollback_statement 表）。
 * <p>每行一条反向语句，回放时分批流式读取 + 逐条幂等（applied_at IS NULL 条件更新）。</p>
 * <p>唯一写入方：sqlaudit-rollback。</p>
 *
 * <p><strong>模型里是明文</strong>：{@code reverseSql} / {@code valueParams} 落库时由
 * JDBC DAO 用 {@code SqlCipher} 加密（库里是密文），读出时解密。旧值参数就是前镜像
 * 生产数据，这是加密的主要动机。</p>
 */
public class RollbackStatement {

    private Long id;
    private Long rollbackId;
    /** 回放顺序（与原行顺序无关，但必须稳定） */
    private Integer stmtOrder;
    /** 带 ? 占位符的参数化语句（明文；DAO 负责加解密） */
    private String reverseSql;
    /** 该条语句的旧值参数 JSON（明文；DAO 负责加解密） */
    private String valueParams;
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

    public String getReverseSql() {
        return reverseSql;
    }

    public void setReverseSql(String reverseSql) {
        this.reverseSql = reverseSql;
    }

    public String getValueParams() {
        return valueParams;
    }

    public void setValueParams(String valueParams) {
        this.valueParams = valueParams;
    }

    public LocalDateTime getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(LocalDateTime appliedAt) {
        this.appliedAt = appliedAt;
    }
}
