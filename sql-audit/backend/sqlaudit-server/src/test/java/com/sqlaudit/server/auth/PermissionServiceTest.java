package com.sqlaudit.server.auth;

import com.sqlaudit.common.enums.UserStatus;
import com.sqlaudit.storage.dao.PermissionDao;
import com.sqlaudit.storage.model.Permission;
import com.sqlaudit.storage.model.PermissionApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionService} 单测（纯内存 fake DAO）：判定快照的加载与四条判定语义
 * （绑定缺失 / all_users / 角色授权并集 / meta 权限持有）。
 */
class PermissionServiceTest {

    private FakePermissionDao dao;
    private PermissionService service;

    private static final CurrentUser USER = new CurrentUser(7, "u", "U", UserStatus.ACTIVE,
            List.of(1L, 2L), List.of("ROLE_A", "ROLE_B"));

    @BeforeEach
    void setUp() {
        dao = new FakePermissionDao();
        service = new PermissionService(dao);
    }

    @Test
    void noBinding_denies() {
        PermissionService.Decision d = service.decide(USER, "GET", "/api/nothing");
        assertFalse(d.anyBinding());
        assertFalse(service.canCall(USER, "GET", "/api/nothing"));
    }

    @Test
    void allUsersPermission_allowsEveryone_withoutAuthorizingRoles() {
        dao.permission(10, "basic", true, false);
        dao.api(10, "GET", "/api/auth/me");
        service.reload();

        PermissionService.Decision d = service.decide(USER, "GET", "/api/auth/me");
        assertTrue(d.anyBinding());
        assertTrue(d.allUsersBound());
        assertTrue(d.authorizingRoleIds().isEmpty());
        assertTrue(service.canCall(USER, "GET", "/api/auth/me"));
    }

    @Test
    void roleGrant_allowsAndReportsAuthorizingRoles() {
        dao.permission(20, "p1", false, false);
        dao.permission(21, "p2", false, false);
        dao.api(20, "POST", "/api/tickets");
        dao.api(21, "POST", "/api/tickets");
        dao.grant(1, 20); // ROLE_A 持有 p1（未持 p2）
        dao.grant(2, 21); // ROLE_B 持有 p2
        service.reload();

        PermissionService.Decision d = service.decide(USER, "POST", "/api/tickets");
        assertTrue(d.anyBinding());
        assertFalse(d.allUsersBound());
        // Set.copyOf 不保证迭代顺序（且角色集合语义本无序），按集合断言
        assertEquals(java.util.Set.of(1L, 2L), d.authorizingRoleIds());
        assertTrue(service.canCall(USER, "POST", "/api/tickets"));

        // 换一个没有角色的用户 → 拒绝
        CurrentUser nobody = new CurrentUser(8, "n", "N", UserStatus.ACTIVE,
                List.of(9L), List.of("ROLE_X"));
        assertFalse(service.canCall(nobody, "POST", "/api/tickets"));
    }

    @Test
    void holdsMetaPermission_viaAnyRole() {
        dao.permission(30, "rbac.manage", false, true);
        dao.grant(1, 30); // ROLE_A 持有 meta
        service.reload();

        assertTrue(service.holdsMetaPermission(USER));

        CurrentUser without = new CurrentUser(8, "n", "N", UserStatus.ACTIVE,
                List.of(9L), List.of("ROLE_X"));
        assertFalse(service.holdsMetaPermission(without));
    }

    @Test
    void listApis_unionOfRoleGrantsAndAllUsers_dedupSorted() {
        dao.permission(10, "basic", true, false);
        dao.permission(20, "p1", false, false);
        dao.api(10, "GET", "/api/auth/me");
        dao.api(20, "POST", "/api/tickets");
        dao.api(20, "GET", "/api/tickets");
        dao.api(10, "GET", "/api/tickets");
        dao.grant(1, 20);
        service.reload();

        List<String> apis = service.listApis(USER);
        assertEquals(List.of("GET /api/auth/me", "GET /api/tickets", "POST /api/tickets"), apis);
    }

    @Test
    void reload_reflectsDaoChanges() {
        dao.permission(20, "p1", false, false);
        dao.api(20, "POST", "/api/tickets");
        assertFalse(service.canCall(USER, "POST", "/api/tickets"));

        dao.grant(1, 20);
        service.reload();
        assertTrue(service.canCall(USER, "POST", "/api/tickets"));
    }

    /** 最小内存替身：目录 / 接口绑定 / 角色授权三张快照的可变实现。 */
    static final class FakePermissionDao implements PermissionDao {

        final List<Permission> permissions = new ArrayList<>();
        final List<PermissionApi> apis = new ArrayList<>();
        final List<long[]> rolePermissionPairs = new ArrayList<>();

        Permission permission(long id, String code, boolean allUsers, boolean meta) {
            Permission p = new Permission();
            p.setId(id);
            p.setCode(code);
            p.setName(code);
            p.setModule("测试");
            p.setAllUsers(allUsers);
            p.setMeta(meta);
            permissions.add(p);
            return p;
        }

        void api(long permissionId, String method, String pattern) {
            apis.add(new PermissionApi(permissionId, method, pattern));
        }

        void grant(long roleId, long permissionId) {
            rolePermissionPairs.add(new long[]{roleId, permissionId});
        }

        @Override
        public List<Permission> listAll() {
            return List.copyOf(permissions);
        }

        @Override
        public Permission findByCode(String code) {
            return permissions.stream().filter(p -> code.equals(p.getCode())).findFirst().orElse(null);
        }

        @Override
        public List<PermissionApi> listAllApis() {
            return List.copyOf(apis);
        }

        @Override
        public List<PermissionApi> listApisByPermission(long permissionId) {
            return apis.stream().filter(a -> a.getPermissionId() == permissionId).toList();
        }

        @Override
        public List<long[]> listRolePermissionPairs() {
            return List.copyOf(rolePermissionPairs);
        }

        @Override
        public List<Long> listRoleIdsByPermission(long permissionId) {
            return rolePermissionPairs.stream()
                    .filter(p -> p[1] == permissionId)
                    .map(p -> p[0])
                    .distinct()
                    .sorted()
                    .toList();
        }
    }
}
