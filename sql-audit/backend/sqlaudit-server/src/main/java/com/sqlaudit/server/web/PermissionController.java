package com.sqlaudit.server.web;

import com.sqlaudit.storage.dao.PermissionDao;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.model.Permission;
import com.sqlaudit.storage.model.PermissionApi;
import com.sqlaudit.storage.model.Role;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限目录端点（{@code rbac.manage} 元权限，契约 §6 角色与权限段）。
 *
 * <p><strong>只读</strong>：权限点目录与接口绑定由 {@code V2__seed.sql}（Flyway DML）
 * 维护，界面只读展示（新增 / 改名 / 改绑定走迁移文件），没有写接口——
 * 「角色 → 权限点」的授权在 {@link RoleController}（经 {@link com.sqlaudit.server.service.RbacService}）。</p>
 */
@RestController
@RequestMapping("/api/permissions")
public class PermissionController {

    private final PermissionDao permissionDao;
    private final RoleDao roleDao;

    public PermissionController(PermissionDao permissionDao, RoleDao roleDao) {
        this.permissionDao = permissionDao;
        this.roleDao = roleDao;
    }

    /** 权限目录：按 module 分组的权限点、各自绑定的接口、持有的角色。 */
    @GetMapping
    public List<PermissionView> catalog() {
        Map<Long, Role> rolesById = new HashMap<>();
        for (Role role : roleDao.listAll()) {
            rolesById.put(role.getId(), role);
        }
        List<PermissionView> out = new ArrayList<>();
        for (Permission p : permissionDao.listAll()) {
            List<ApiView> apis = new ArrayList<>();
            for (PermissionApi api : permissionDao.listApisByPermission(p.getId())) {
                apis.add(new ApiView(api.getHttpMethod(), api.getUrlPattern()));
            }
            List<RoleRefView> roles = new ArrayList<>();
            for (Long roleId : permissionDao.listRoleIdsByPermission(p.getId())) {
                Role role = rolesById.get(roleId);
                if (role != null) {
                    roles.add(new RoleRefView(role.getId(), role.getCode(), role.getName()));
                }
            }
            out.add(new PermissionView(p.getId(), p.getCode(), p.getName(), p.getModule(),
                    p.getDescription(), p.getSort(), p.isAllUsers(), p.isMeta(), apis, roles));
        }
        return out;
    }

    /** 权限点目录视图（契约 §6：{id, code, name, module, description, sort, allUsers, meta, apis, roles}）。 */
    public record PermissionView(Long id, String code, String name, String module, String description,
                                 int sort, boolean allUsers, boolean meta, List<ApiView> apis,
                                 List<RoleRefView> roles) {
    }

    /** 接口绑定视图。 */
    public record ApiView(String method, String pattern) {
    }

    /** 持有角色视图。 */
    public record RoleRefView(Long id, String code, String name) {
    }
}
