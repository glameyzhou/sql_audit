package com.sqlaudit.server.web;

import com.sqlaudit.audit.AuditWriter;
import com.sqlaudit.common.enums.AuditAction;
import com.sqlaudit.common.enums.AuditResult;
import com.sqlaudit.common.enums.AuditTargetType;
import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.enums.PermissionCode;
import com.sqlaudit.common.model.AuditEvent;
import com.sqlaudit.server.auth.PermissionService;
import com.sqlaudit.server.auth.RequirePermission;
import com.sqlaudit.server.auth.RoleAuthInterceptor;
import com.sqlaudit.storage.dao.PermissionDao;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.model.Permission;
import com.sqlaudit.storage.model.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

/**
 * 权限配置端点（元权限 {@code PERMISSION_ADMIN}，硬编码 ADMIN，不可在界面配空）。
 *
 * <p>GET 返回 9 权限点 × 角色 的矩阵（含 {@code customized} 与生效角色，未配置回显代码兜底）；
 * POST 批量保存角色-权限点映射，写 {@code t_role_permission} + 置 {@code customized} +
 * 失效 {@link PermissionService} 缓存 + 写审计 {@code PERMISSION_ASSIGN}。</p>
 */
@RestController
@RequestMapping("/api/permissions")
@RequirePermission(PermissionCode.PERMISSION_ADMIN)
public class PermissionController {

    private static final Logger log = LoggerFactory.getLogger(PermissionController.class);

    private final PermissionDao permissionDao;
    private final RoleDao roleDao;
    private final PermissionService permissionService;
    private final AuditWriter auditWriter;
    private final ZoneId zone;

    public PermissionController(PermissionDao permissionDao, RoleDao roleDao,
                                PermissionService permissionService, AuditWriter auditWriter, ZoneId zone) {
        this.permissionDao = permissionDao;
        this.roleDao = roleDao;
        this.permissionService = permissionService;
        this.auditWriter = auditWriter;
        this.zone = zone;
    }

    /** 权限矩阵：每行 { code, name, description, configurable, customized, roles }。 */
    @GetMapping
    public List<Map<String, Object>> matrix() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Permission p : permissionDao.listAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", p.getCode());
            m.put("name", p.getName());
            m.put("description", p.getDescription());
            m.put("configurable", configurable(p.getCode()));
            m.put("customized", p.isCustomized());
            m.put("roles", permissionService.allowedRoles(permissionOf(p.getCode())));
            out.add(m);
        }
        return out;
    }

    /** 批量保存角色-权限点映射（逐权限点写审计）。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody List<SaveItem> items,
                                                    HttpServletRequest request) {
        Map<String, Long> roleIdByCode = new HashMap<>();
        for (Role role : roleDao.listAll()) {
            roleIdByCode.put(role.getCode(), role.getId());
        }

        List<Map<String, Object>> applied = new ArrayList<>();
        for (SaveItem item : items) {
            PermissionCode pc = permissionOf(item.code());
            if (pc == null || !pc.isConfigurable()) {
                continue; // 元权限不可配置，静默跳过（前端已只读）
            }
            Permission p = permissionDao.findByCode(item.code());
            if (p == null) {
                continue;
            }
            List<Long> roleIds = new ArrayList<>();
            if (item.roles() != null) {
                for (String code : item.roles()) {
                    Long id = roleIdByCode.get(code);
                    if (id == null) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "code", ErrorCode.INVALID_REQUEST.getCode(),
                                "message", "未知角色: " + code));
                    }
                    roleIds.add(id);
                }
            }
            permissionDao.replaceRolePermissions(p.getId(), roleIds, currentUserId(request));
            permissionService.invalidate(item.code());
            audit(request, item.code(), item.roles());
            applied.add(Map.of("code", item.code(), "roles", item.roles() == null ? List.of() : item.roles()));
        }
        return ResponseEntity.ok(Map.of("applied", applied));
    }

    // ---------------- 内部工具 ----------------

    private static PermissionCode permissionOf(String code) {
        if (code == null) {
            return null;
        }
        try {
            return PermissionCode.fromCode(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean configurable(String code) {
        PermissionCode pc = permissionOf(code);
        return pc != null && pc.isConfigurable();
    }

    private void audit(HttpServletRequest request, String code, List<String> roles) {
        try {
            Long actorId = (Long) request.getAttribute(RoleAuthInterceptor.ATTR_USER_ID);
            String actorName = (String) request.getAttribute(RoleAuthInterceptor.ATTR_USERNAME);
            @SuppressWarnings("unchecked")
            List<String> actorRoles = (List<String>) request.getAttribute(RoleAuthInterceptor.ATTR_ROLES);
            String actorRole = actorRoles != null && !actorRoles.isEmpty() ? actorRoles.get(0) : null;
            AuditEvent event = AuditEvent.builder()
                    .actorId(actorId == null ? 0L : actorId)
                    .actorName(actorName == null ? AuditEvent.SYSTEM_ACTOR_NAME : actorName)
                    .actorRole(actorRole == null ? AuditEvent.SYSTEM_ACTOR_ROLE : actorRole)
                    .action(AuditAction.PERMISSION_ASSIGN)
                    .targetType(AuditTargetType.PERMISSION)
                    .targetId(code)
                    .detailPlaintext("{\"code\":\"" + code + "\",\"roles\":" + jsonArray(roles) + "}")
                    .result(AuditResult.SUCCESS)
                    .traceId("permission-" + code)
                    .createdAt(OffsetDateTime.now(zone))
                    .build();
            auditWriter.append(event);
        } catch (Exception e) {
            log.warn("权限配置审计失败 code={}: {}", code, e.getMessage());
        }
    }

    private static Long currentUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(RoleAuthInterceptor.ATTR_USER_ID);
        return userId == null ? null : userId;
    }

    private static String jsonArray(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(codes.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    /** 单个权限点保存项：{ code, roles:[角色 code] }。 */
    public record SaveItem(String code, List<String> roles) {
    }
}
