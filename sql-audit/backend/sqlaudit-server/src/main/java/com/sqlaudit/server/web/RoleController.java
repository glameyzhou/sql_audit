package com.sqlaudit.server.web;

import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.enums.InstanceScope;
import com.sqlaudit.server.auth.AuditActor;
import com.sqlaudit.server.auth.CurrentUser;
import com.sqlaudit.server.auth.RoleAuthInterceptor;
import com.sqlaudit.server.service.RbacService;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.model.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 角色管理端点（{@code rbac.manage} 元权限，契约 §6 角色与权限段）。
 *
 * <p>角色完全数据化：{@code code} 是纯数据（唯一），内置角色（{@code builtin}）不可删除、
 * code 不可改；功能权限（{@code permissionIds}）与数据范围（{@code instanceScope} +
 * {@code instanceIds}）随角色整体替换。所有写操作走 {@link RbacService}（防自锁不变式，
 * 违反 409 {@code RBAC_LOCKOUT} 整笔回滚）。</p>
 */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleDao roleDao;
    private final RbacService rbacService;

    public RoleController(RoleDao roleDao, RbacService rbacService) {
        this.roleDao = roleDao;
        this.rbacService = rbacService;
    }

    /** 全部角色（含功能权限、数据范围与成员数）。 */
    @GetMapping
    public List<RoleView> list() {
        List<RoleView> out = new ArrayList<>();
        for (Role role : roleDao.listAll()) {
            out.add(viewOf(role));
        }
        return out;
    }

    /** 新建自定义角色。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody RoleRequest req,
                                                      HttpServletRequest request) {
        String code = req.code() == null ? "" : req.code().trim();
        if (code.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", ErrorCode.INVALID_REQUEST.getCode(), "message", "角色 code 不能为空"));
        }
        if (roleDao.findByCode(code) != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", ErrorCode.BUSINESS_ERROR.getCode(), "message", "角色 code 已存在: " + code));
        }
        CurrentUser user = RoleAuthInterceptor.currentUser(request);
        AuditActor actor = AuditActor.of(user, request);
        // 服务层再做一次判重（并发窗口兜底）；写操作走防自锁事务
        long id = rbacService.createRole(code, req.name(), req.description(),
                req.instanceScope(), req.permissionIds(), req.instanceIds(), actor);
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 更新角色（名称 / 说明 / 数据范围 / 权限点 / 实例授权整体替换；code 与 builtin 不可改）。 */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @RequestBody RoleRequest req,
                                                      HttpServletRequest request) {
        Role existing = roleDao.findById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        CurrentUser user = RoleAuthInterceptor.currentUser(request);
        rbacService.updateRole(id, req.name(), req.description(), req.instanceScope(),
                req.permissionIds(), req.instanceIds(), AuditActor.of(user, request));
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 删除角色（内置 400；防自锁 409）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id, HttpServletRequest request) {
        Role existing = roleDao.findById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (existing.isBuiltin()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", ErrorCode.BUILTIN_ROLE_IMMUTABLE.getCode(), "message", "内置角色不可删除"));
        }
        CurrentUser user = RoleAuthInterceptor.currentUser(request);
        rbacService.deleteRole(id, AuditActor.of(user, request));
        return ResponseEntity.ok(Map.of("id", id));
    }

    // ---------------- 视图 ----------------

    /** 角色视图（契约 §6：含权限点 / 实例授权 / 成员数）。 */
    public record RoleView(Long id, String code, String name, String description, boolean builtin,
                           InstanceScope instanceScope, List<Long> permissionIds,
                           List<Long> instanceIds, int userCount) {
    }

    private RoleView viewOf(Role role) {
        return new RoleView(role.getId(), role.getCode(), role.getName(), role.getDescription(),
                role.isBuiltin(), role.getInstanceScope(),
                roleDao.listPermissionIds(role.getId()), roleDao.listInstanceIds(role.getId()),
                roleDao.countUsers(role.getId()));
    }

    /** 角色写请求（契约 §6；code 仅在创建时出现，内置角色 code 不可改）。 */
    public record RoleRequest(String code, String name, String description,
                              InstanceScope instanceScope, List<Long> permissionIds,
                              List<Long> instanceIds) {
    }
}
