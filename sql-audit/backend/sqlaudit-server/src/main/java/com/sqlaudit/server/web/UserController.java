package com.sqlaudit.server.web;

import com.sqlaudit.audit.AuditWriter;
import com.sqlaudit.common.model.AuditEvent;
import com.sqlaudit.server.auth.RequireRole;
import com.sqlaudit.server.auth.RoleAuthInterceptor;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 手工用户管理端点（仅 ADMIN）：创建本地账号、分配角色、改密码、停用/启用。
 *
 * <p>企微 OAuth 暂不在此列（source=LOCAL 本地应急账号）。密码用 BCrypt 存哈希，
 * 角色经 {@link RoleDao} 以「删旧 + 插新」方式重设，写审计
 * （USER_CREATE / USER_UPDATE / PASSWORD_RESET / ROLE_ASSIGN）。</p>
 */
@RestController
@RequestMapping("/api/users")
@RequireRole("ADMIN")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserDao userDao;
    private final RoleDao roleDao;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuditWriter auditWriter;
    private final ZoneId zone;

    public UserController(UserDao userDao, RoleDao roleDao, BCryptPasswordEncoder passwordEncoder,
                          AuditWriter auditWriter, ZoneId zone) {
        this.userDao = userDao;
        this.roleDao = roleDao;
        this.passwordEncoder = passwordEncoder;
        this.auditWriter = auditWriter;
        this.zone = zone;
    }

    /** 用户列表（含角色 code 列表）。 */
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
            m.put("roles", roleDao.listByUserId(u.getId()).stream().map(Role::getCode).toList());
            out.add(m);
        }
        return out;
    }

    /** 创建本地账号：username 重复 → 409；BCrypt 存密码；插 user + user_role。 */
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
                    "code", "USERNAME_EXISTS", "message", "用户名已存在"));
        }
        List<Long> roleIds;
        try {
            roleIds = resolveRoleIds(req.roles());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        User user = new User();
        user.setUsername(username);
        user.setDisplayName(req.displayName() == null || req.displayName().isBlank()
                ? username : req.displayName().trim());
        user.setEmail(req.email());
        user.setSource("LOCAL");
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setStatus("ACTIVE");
        long id = userDao.insert(user);
        roleDao.assignRoles(id, roleIds, currentUserId(request));
        audit(request, "USER_CREATE", id, "{\"username\":\"" + username + "\"}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 重置密码（admin 重置）。 */
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
        userDao.updatePassword(id, passwordEncoder.encode(req.password()));
        audit(request, "PASSWORD_RESET", id, "{\"username\":\"" + user.getUsername() + "\"}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 改 displayName / email / status（启用/停用）。 */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @RequestBody UpdateUserRequest req,
                                                      HttpServletRequest request) {
        if (userDao.findById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        if (req.displayName() != null) {
            userDao.updateProfile(id, req.displayName().trim(), req.email());
        }
        if (req.status() != null && !req.status().isBlank()) {
            userDao.updateStatus(id, req.status());
        }
        audit(request, "USER_UPDATE", id, "{\"id\":" + id + "}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 重设角色：删除旧 user_role + 插入新 roleIds。 */
    @PutMapping("/{id}/roles")
    public ResponseEntity<Map<String, Object>> assignRoles(@PathVariable long id,
                                                           @RequestBody AssignRolesRequest req,
                                                           HttpServletRequest request) {
        if (userDao.findById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        List<Long> roleIds;
        try {
            roleIds = resolveRoleIds(req.roles());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        roleDao.removeAllRoles(id);
        roleDao.assignRoles(id, roleIds, currentUserId(request));
        audit(request, "ROLE_ASSIGN", id, "{\"roles\":" + jsonArray(req.roles()) + "}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    // ---------------- 内部工具 ----------------

    private List<Long> resolveRoleIds(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        Map<String, Long> byCode = new HashMap<>();
        for (Role role : roleDao.listAll()) {
            byCode.put(role.getCode(), role.getId());
        }
        List<Long> ids = new ArrayList<>(codes.size());
        for (String code : codes) {
            Long id = byCode.get(code);
            if (id == null) {
                throw new IllegalArgumentException("未知角色: " + code);
            }
            ids.add(id);
        }
        return ids;
    }

    private void audit(HttpServletRequest request, String action, long targetId, String detail) {
        try {
            Long actorId = (Long) request.getAttribute(RoleAuthInterceptor.ATTR_USER_ID);
            String actorName = (String) request.getAttribute(RoleAuthInterceptor.ATTR_USERNAME);
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) request.getAttribute(RoleAuthInterceptor.ATTR_ROLES);
            String actorRole = roles != null && !roles.isEmpty() ? roles.get(0) : null;
            AuditEvent event = AuditEvent.builder()
                    .actorId(actorId == null ? 0L : actorId)
                    .actorName(actorName == null ? "system" : actorName)
                    .actorRole(actorRole == null ? "SYSTEM" : actorRole)
                    .action(action)
                    .targetType("user")
                    .targetId(String.valueOf(targetId))
                    .detailPlaintext(detail)
                    .result("SUCCESS")
                    // audit_log.trace_id NOT NULL：用户管理操作链路标识，与 ticket-/config- 前缀口径一致
                    .traceId("user-" + targetId)
                    .createdAt(OffsetDateTime.now(zone))
                    .build();
            auditWriter.append(event);
        } catch (Exception e) {
            log.warn("用户管理审计失败 action={} targetId={}: {}", action, targetId, e.getMessage());
        }
    }

    private static Long currentUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(RoleAuthInterceptor.ATTR_USER_ID);
        return userId == null ? null : userId;
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", "INVALID_REQUEST", "message", message));
    }

    private static String jsonArray(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return "[]";
        }
        return "[" + codes.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(",")) + "]";
    }

    // ---------------- 请求体 ----------------

    public record CreateUserRequest(String username, String displayName, String email,
                                    String password, List<String> roles) {
    }

    public record UpdateUserRequest(String displayName, String email, String status) {
    }

    public record ResetPasswordRequest(String password) {
    }

    public record AssignRolesRequest(List<String> roles) {
    }
}
