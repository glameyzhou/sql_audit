package com.sqlaudit.server.web;

import com.sqlaudit.audit.AuditWriter;
import com.sqlaudit.common.enums.AuditAction;
import com.sqlaudit.common.enums.AuditResult;
import com.sqlaudit.common.enums.AuditTargetType;
import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.enums.UserSource;
import com.sqlaudit.common.enums.UserStatus;
import com.sqlaudit.common.model.AuditEvent;
import com.sqlaudit.server.auth.AuditActor;
import com.sqlaudit.server.auth.AuditEvents;
import com.sqlaudit.server.auth.CurrentUser;
import com.sqlaudit.server.auth.RoleAuthInterceptor;
import com.sqlaudit.server.service.RbacService;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.model.Role;
import com.sqlaudit.storage.model.User;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户管理端点（契约 §6 用户段）。{@code user.manage} 权限点覆盖列表 / 创建 / 改资料 /
 * 重置密码；分配角色（{@code PUT /api/users/{id}/roles}）是 {@code rbac.manage} 元权限。
 *
 * <p>创建不带角色（角色单独分配）；停用走 {@link RbacService#updateUserStatus}（防自锁
 * 不变式）；对「持有 meta 权限点的用户」改密码 / 改资料 / 停用要求操作者同样持有 meta
 * 权限点（{@link RbacService#requirePrivilegedActor}，403）。身份一律取登录态，审计写
 * 真实操作人（clientIp / userAgent）。</p>
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserDao userDao;
    private final RoleDao roleDao;
    private final RbacService rbacService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuditWriter auditWriter;
    private final ZoneId zone;

    public UserController(UserDao userDao, RoleDao roleDao, RbacService rbacService,
                          BCryptPasswordEncoder passwordEncoder, AuditWriter auditWriter, ZoneId zone) {
        this.userDao = userDao;
        this.roleDao = roleDao;
        this.rbacService = rbacService;
        this.passwordEncoder = passwordEncoder;
        this.auditWriter = auditWriter;
        this.zone = zone;
    }

    /** 用户列表（含角色与最后登录时间）。 */
    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : userDao.listAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("displayName", u.getDisplayName());
            m.put("email", u.getEmail());
            m.put("source", u.getSource());
            m.put("status", u.getStatus());
            m.put("roles", roleDao.listByUserId(u.getId()).stream()
                    .map(r -> Map.of("code", r.getCode(), "name", r.getName()))
                    .toList());
            m.put("lastLoginAt", u.getLastLoginAt());
            out.add(m);
        }
        return out;
    }

    /** 创建本地账号（<strong>不带角色</strong>，角色经 rbac.manage 单独分配）。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateUserRequest req,
                                                      HttpServletRequest request) {
        if (req.username() == null || req.username().isBlank()) {
            return badRequest("用户名不能为空");
        }
        if (req.password() == null || req.password().isBlank()) {
            return badRequest("密码不能为空");
        }
        String username = req.username().trim();
        if (userDao.findByUsername(username) != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", ErrorCode.USERNAME_EXISTS.getCode(), "message", "用户名已存在"));
        }

        User user = new User();
        user.setUsername(username);
        user.setDisplayName(req.displayName() == null || req.displayName().isBlank()
                ? username : req.displayName().trim());
        user.setEmail(req.email());
        user.setSource(UserSource.LOCAL);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setStatus(UserStatus.ACTIVE);
        long id = userDao.insert(user);
        audit(request, AuditAction.USER_CREATE, id, "{\"username\":\"" + username + "\"}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 重置密码（admin 重置；目标持有 meta 权限点 → 操作者须持有 meta，403）。 */
    @PutMapping("/{id}/password")
    public ResponseEntity<Map<String, Object>> resetPassword(@PathVariable long id,
                                                             @RequestBody ResetPasswordRequest req,
                                                             HttpServletRequest request) {
        if (req.password() == null || req.password().isBlank()) {
            return badRequest("密码不能为空");
        }
        User user = userDao.findById(id);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        CurrentUser operator = RoleAuthInterceptor.currentUser(request);
        rbacService.requirePrivilegedActor(operator, id);
        userDao.updatePassword(id, passwordEncoder.encode(req.password()));
        audit(request, AuditAction.PASSWORD_RESET, id, "{\"username\":\"" + user.getUsername() + "\"}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 改 displayName / email / status（启用/停用；停用走防自锁不变式）。 */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @RequestBody UpdateUserRequest req,
                                                      HttpServletRequest request) {
        if (userDao.findById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        CurrentUser operator = RoleAuthInterceptor.currentUser(request);
        AuditActor actor = AuditActor.of(operator, request);
        if (req.displayName() != null || req.email() != null) {
            rbacService.requirePrivilegedActor(operator, id);
            userDao.updateProfile(id,
                    req.displayName() == null
                            ? userDao.findById(id).getDisplayName() : req.displayName().trim(),
                    req.email());
        }
        if (req.status() != null) {
            // 启停用是 RBAC 写路径：防自锁不变式 + 串行化 + 即时失效
            rbacService.updateUserStatus(id, req.status(), actor);
        }
        audit(request, AuditAction.USER_UPDATE, id, "{\"id\":" + id + "}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 分配角色（rbac.manage 元权限；整体替换 + 防自锁）。 */
    @PutMapping("/{id}/roles")
    public ResponseEntity<Map<String, Object>> assignRoles(@PathVariable long id,
                                                           @RequestBody AssignRolesRequest req,
                                                           HttpServletRequest request) {
        if (userDao.findById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        CurrentUser user = RoleAuthInterceptor.currentUser(request);
        rbacService.replaceUserRoles(id, req.roles() == null ? List.of() : req.roles(),
                AuditActor.of(user, request));
        return ResponseEntity.ok(Map.of("id", id));
    }

    // ---------------- 内部工具 ----------------

    private void audit(HttpServletRequest request, AuditAction action, long targetId, String detail) {
        try {
            AuditActor actor = AuditActor.of(RoleAuthInterceptor.currentUser(request), request);
            auditWriter.append(AuditEvents.base(actor)
                    .action(action)
                    .targetType(AuditTargetType.USER)
                    .targetId(String.valueOf(targetId))
                    .detailPlaintext(detail)
                    .result(AuditResult.SUCCESS)
                    .traceId("user-" + targetId)
                    .createdAt(OffsetDateTime.now(zone))
                    .build());
        } catch (Exception e) {
            log.warn("用户管理审计失败 action={} targetId={}: {}", action.getCode(), targetId, e.getMessage());
        }
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", ErrorCode.INVALID_REQUEST.getCode(), "message", message));
    }

    // ---------------- 请求体 ----------------

    public record CreateUserRequest(String username, String displayName, String email,
                                    String password) {
    }

    public record UpdateUserRequest(String displayName, String email, UserStatus status) {
    }

    public record ResetPasswordRequest(String password) {
    }

    public record AssignRolesRequest(List<String> roles) {
    }
}
