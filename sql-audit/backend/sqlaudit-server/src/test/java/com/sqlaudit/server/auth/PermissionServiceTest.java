package com.sqlaudit.server.auth;

import com.sqlaudit.common.enums.PermissionCode;
import com.sqlaudit.common.enums.RoleCode;
import com.sqlaudit.storage.impl.memory.InMemoryPermissionDao;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PermissionService} 单测：兜底 vs 覆盖的判定语义（JUnit 每用例新建实例，互不干扰）。
 */
class PermissionServiceTest {

    private final InMemoryPermissionDao dao = new InMemoryPermissionDao();
    private final PermissionService service = new PermissionService(dao);

    @Test
    void fallback_usesDefaultRoles() {
        // 未配置：CONFIG_RULE_FLOW 默认 DBA/ADMIN
        assertThat(service.hasPermission(List.of("DBA"), PermissionCode.CONFIG_RULE_FLOW)).isTrue();
        assertThat(service.hasPermission(List.of("ADMIN"), PermissionCode.CONFIG_RULE_FLOW)).isTrue();
        assertThat(service.hasPermission(List.of("DEV"), PermissionCode.CONFIG_RULE_FLOW)).isFalse();
        assertThat(service.hasPermission(List.of("LEAD"), PermissionCode.CONFIG_RULE_FLOW)).isFalse();
    }

    @Test
    void override_replacesFallback() {
        long id = dao.findByCode(PermissionCode.CONFIG_RULE_FLOW.getCode()).getId();
        // 1L = DEV（与 InMemoryRoleDao 种子 id 一致）
        dao.replaceRolePermissions(id, List.of(1L), null);
        service.invalidate(PermissionCode.CONFIG_RULE_FLOW.getCode());

        assertThat(service.hasPermission(List.of("DEV"), PermissionCode.CONFIG_RULE_FLOW)).isTrue();
        assertThat(service.hasPermission(List.of("DBA"), PermissionCode.CONFIG_RULE_FLOW)).isFalse();
    }

    @Test
    void override_emptyClosesPermission() {
        long id = dao.findByCode(PermissionCode.EXECUTE.getCode()).getId();
        dao.replaceRolePermissions(id, List.of(), null);
        service.invalidate(PermissionCode.EXECUTE.getCode());

        assertThat(service.hasPermission(List.of("DBA"), PermissionCode.EXECUTE)).isFalse();
        assertThat(service.hasPermission(List.of("ADMIN"), PermissionCode.EXECUTE)).isFalse();
    }

    @Test
    void metaPermission_alwaysAdminOnly() {
        // PERMISSION_ADMIN 不可配置，硬编码 ADMIN
        assertThat(service.hasPermission(List.of("ADMIN"), PermissionCode.PERMISSION_ADMIN)).isTrue();
        assertThat(service.hasPermission(List.of("DEV"), PermissionCode.PERMISSION_ADMIN)).isFalse();
        assertThat(service.hasPermission(List.of("DBA"), PermissionCode.PERMISSION_ADMIN)).isFalse();
    }

    @Test
    void defaultRoles_alignWithMatrix() {
        // 兜底默认值必须与设计文档 §8.2 权限矩阵一致（迁移不改行为）
        assertThat(PermissionCode.INSTANCE_ADMIN.getDefaultRoles()).containsExactly(RoleCode.ADMIN);
        assertThat(PermissionCode.USER_ADMIN.getDefaultRoles()).containsExactly(RoleCode.ADMIN);
        assertThat(PermissionCode.EXECUTE.getDefaultRoles()).containsExactly(RoleCode.DBA, RoleCode.ADMIN);
        assertThat(PermissionCode.CONFIG_RULE_FLOW.getDefaultRoles()).containsExactly(RoleCode.DBA, RoleCode.ADMIN);
        assertThat(PermissionCode.AUDIT_EXPORT.getDefaultRoles()).containsExactly(RoleCode.DBA, RoleCode.ADMIN);
        assertThat(PermissionCode.APPROVE.getDefaultRoles()).containsExactly(RoleCode.LEAD, RoleCode.DBA, RoleCode.ADMIN);
        assertThat(PermissionCode.TICKET_SUBMIT.getDefaultRoles())
                .containsExactly(RoleCode.DEV, RoleCode.LEAD, RoleCode.DBA, RoleCode.ADMIN);
    }
}
