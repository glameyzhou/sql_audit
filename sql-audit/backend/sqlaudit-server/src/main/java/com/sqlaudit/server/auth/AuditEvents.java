package com.sqlaudit.server.auth;

import com.sqlaudit.common.model.AuditEvent;

/**
 * 审计事件构造工具：控制器 / 服务构造 {@link AuditEvent} 统一从 {@link AuditActor} 预填
 * 操作人四件套（actor id / 用户名 / 角色 / clientIp / userAgent），避免各处手抄。
 */
public final class AuditEvents {

    private AuditEvents() {
    }

    /** 预填操作人字段的 builder（action / targetType / targetId / detail 由调用方继续填）。 */
    public static AuditEvent.Builder base(AuditActor actor) {
        return AuditEvent.builder()
                .actorId(actor.id())
                .actorName(actor.name() == null ? AuditEvent.SYSTEM_ACTOR_NAME : actor.name())
                .actorRole(actor.roleCode() == null ? AuditEvent.SYSTEM_ACTOR_ROLE : actor.roleCode())
                .clientIp(actor.clientIp())
                .userAgent(actor.userAgent());
    }
}
