package com.sqlaudit.server.web;

import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.enums.PermissionCode;
import com.sqlaudit.common.enums.RoleCode;
import com.sqlaudit.server.auth.RequirePermission;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.model.Role;
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

import java.util.List;
import java.util.Map;

/**
 * 角色管理端点（仅 ADMIN，{@code USER_ADMIN} 权限）。
 *
 * <p>自定义角色：code 为自由字符串（不可与内置 DEV/LEAD/DBA/ADMIN 冲突），rank 为审批链
 * 「取最严」权重。内置角色不可删除、code 不可改（被 {@link RoleCode} / 种子 / 权限兜底引用）。</p>
 */
@RestController
@RequestMapping("/api/roles")
@RequirePermission(PermissionCode.USER_ADMIN)
public class RoleController {

    private final RoleDao roleDao;

    public RoleController(RoleDao roleDao) {
        this.roleDao = roleDao;
    }

    /** 全部角色（含 rank，供权限矩阵动态列 / 用户分配角色下拉）。 */
    @GetMapping
    public List<Role> list() {
        return roleDao.listAll();
    }

    /** 新建自定义角色。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody RoleRequest req) {
        if (req.code() == null || req.code().isBlank()) {
            return badRequest("角色 code 不能为空");
        }
        String code = req.code().trim();
        if (isBuiltIn(code) || roleDao.findByCode(code) != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", ErrorCode.INVALID_REQUEST.getCode(), "message", "角色 code 已存在或为内置角色"));
        }
        Role role = new Role();
        role.setCode(code);
        role.setName(req.name() == null || req.name().isBlank() ? code : req.name().trim());
        role.setDescription(req.description());
        role.setRank(req.rank() == null ? 0 : req.rank());
        long id = roleDao.insert(role);
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 更新角色（名称/说明/权重；code 不可改）。 */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id, @RequestBody RoleRequest req) {
        Role existing = roleDao.listAll().stream().filter(r -> r.getId() == id).findFirst().orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (isBuiltIn(existing.getCode()) && req.code() != null && !req.code().equals(existing.getCode())) {
            return badRequest("内置角色 code 不可修改");
        }
        Role role = new Role();
        role.setId(id);
        role.setName(req.name());
        role.setDescription(req.description());
        role.setRank(req.rank());
        roleDao.update(role);
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 删除自定义角色（内置角色不可删除）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id) {
        Role existing = roleDao.listAll().stream().filter(r -> r.getId() == id).findFirst().orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (isBuiltIn(existing.getCode())) {
            return badRequest("内置角色不可删除");
        }
        roleDao.delete(id);
        return ResponseEntity.ok(Map.of("id", id));
    }

    private static boolean isBuiltIn(String code) {
        try {
            RoleCode.fromCode(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", ErrorCode.INVALID_REQUEST.getCode(), "message", message));
    }

    public record RoleRequest(String code, String name, String description, Integer rank) {
    }
}
