package com.sqlaudit.storage.model;

import java.time.LocalDateTime;

/**
 * 审计链头实体（对应 chain_head 表，README §4.1 单写入点）。
 * <p>单行表（恒 id=1），行锁串行化并发写，seq 连续无空洞。</p>
 * <p>唯一写入方：sqlaudit-audit。</p>
 */
public class ChainHead {

    private Integer id;
    private Long lastSeq;
    private String lastHash;
    private LocalDateTime updatedAt;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Long getLastSeq() {
        return lastSeq;
    }

    public void setLastSeq(Long lastSeq) {
        this.lastSeq = lastSeq;
    }

    public String getLastHash() {
        return lastHash;
    }

    public void setLastHash(String lastHash) {
        this.lastHash = lastHash;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
