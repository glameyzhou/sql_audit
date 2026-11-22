package com.sqlaudit.common.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/**
 * 审计事件（设计文档 §7.2 / §7.3 的 canonical 记录）。
 *
 * <p><strong>字段顺序即哈希顺序，一旦上线不可改</strong>（§7.3 第 3 点）：
 * 修改字段序会导致历史链全部断裂。新增字段只能追加到末尾，不能插入中间。</p>
 *
 * <p>canonical JSON 规则（§7.3）：</p>
 * <ul>
 *   <li>字段固定顺序：seq, actor_id, actor_name, actor_role, action, target_type,
 *       target_id, instance_id, detail_plaintext, result, client_ip, user_agent,
 *       trace_id, created_at；</li>
 *   <li>UTF-8，无缩进无多余空格（{@code {"k":"v"}}）；</li>
 *   <li>NULL 序列化为 JSON null（不省略键）；</li>
 *   <li>created_at 用 {@code yyyy-MM-dd'T'HH:mm:ss.SSSXXX}，毫秒满 3 位；</li>
 *   <li>数字不加引号，布尔用 true/false。</li>
 * </ul>
 *
 * <p>哈希算明文不算密文：{@code detailPlaintext} 是明文（存储时由 sqlaudit-audit
 * 加密后落 {@code audit_log.detail}），参与哈希的是明文，密钥轮换不破链。</p>
 */
@JsonPropertyOrder({
        "seq", "actorId", "actorName", "actorRole", "action", "targetType", "targetId",
        "instanceId", "detailPlaintext", "result", "clientIp", "userAgent", "traceId", "createdAt"
})
public class AuditEvent {

    /** 链序号，由 chain_head 行锁内分配（非 AUTO_INCREMENT id） */
    private final long seq;
    private final long actorId;
    private final String actorName;
    private final String actorRole;
    private final String action;
    private final String targetType;
    private final String targetId;
    private final Long instanceId;
    /** 明文 detail；存储时才加密 */
    private final String detailPlaintext;
    private final String result;
    private final String clientIp;
    private final String userAgent;
    private final String traceId;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private final OffsetDateTime createdAt;

    private AuditEvent(Builder b) {
        this.seq = b.seq;
        this.actorId = b.actorId;
        this.actorName = b.actorName;
        this.actorRole = b.actorRole;
        this.action = b.action;
        this.targetType = b.targetType;
        this.targetId = b.targetId;
        this.instanceId = b.instanceId;
        this.detailPlaintext = b.detailPlaintext;
        this.result = b.result;
        this.clientIp = b.clientIp;
        this.userAgent = b.userAgent;
        this.traceId = b.traceId;
        this.createdAt = b.createdAt;
    }

    public long getSeq() {
        return seq;
    }

    public long getActorId() {
        return actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public String getActorRole() {
        return actorRole;
    }

    public String getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public String getDetailPlaintext() {
        return detailPlaintext;
    }

    public String getResult() {
        return result;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getTraceId() {
        return traceId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long seq;
        private long actorId;
        private String actorName;
        private String actorRole;
        private String action;
        private String targetType;
        private String targetId;
        private Long instanceId;
        private String detailPlaintext;
        private String result;
        private String clientIp;
        private String userAgent;
        private String traceId;
        private OffsetDateTime createdAt;

        public Builder seq(long seq) {
            this.seq = seq;
            return this;
        }

        public Builder actorId(long actorId) {
            this.actorId = actorId;
            return this;
        }

        public Builder actorName(String actorName) {
            this.actorName = actorName;
            return this;
        }

        public Builder actorRole(String actorRole) {
            this.actorRole = actorRole;
            return this;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public Builder targetType(String targetType) {
            this.targetType = targetType;
            return this;
        }

        public Builder targetId(String targetId) {
            this.targetId = targetId;
            return this;
        }

        public Builder instanceId(Long instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        public Builder detailPlaintext(String detailPlaintext) {
            this.detailPlaintext = detailPlaintext;
            return this;
        }

        public Builder result(String result) {
            this.result = result;
            return this;
        }

        public Builder clientIp(String clientIp) {
            this.clientIp = clientIp;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder createdAt(OffsetDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public AuditEvent build() {
            return new AuditEvent(this);
        }
    }
}
