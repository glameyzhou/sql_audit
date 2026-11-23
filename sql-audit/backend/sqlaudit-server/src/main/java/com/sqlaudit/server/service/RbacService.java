package com.sqlaudit.server.service;

import com.sqlaudit.audit.AuditWriter;
import com.sqlaudit.common.enums.AuditAction;
import com.sqlaudit.common.enums.AuditResult;
import com.sqlaudit.common.enums.AuditTargetType;
import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.enums.InstanceScope;
import com.sqlaudit.common.enums.UserStatus;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.common.model.AuditEvent;
import com.sqlaudit.server.auth.AuditActor;
import com.sqlaudit.server.auth.AuditEvents;
import com.sqlaudit.server.auth.CurrentUser;
import com.sqlaudit.server.auth.CurrentUserService;
import com.sqlaudit.server.auth.PermissionService;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.model.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RBAC 写服务（契约 §4.3 防自锁）：角色、授权、用户角色的所有写操作收口在这里。
 *
 * <p>防自锁不变式：任一 RBAC 写操作提交后，仍须存在 ≥1 个 ACTIVE 用户经任意角色持有某个
 * {@code meta} 权限点，否则 {@code RBAC_LOCKOUT}（409）整笔回滚。正确性依赖两点：</p>
 * <ol>
 *   <li><strong>同事务串行化</strong>：事务开始先 {@code SELECT ... FROM t_permission
 *       WHERE meta = TRUE FOR UPDATE}（{@link UserDao#lockMetaPermissions}）——并发 RBAC
 *       写排成一条队，后到者在前者提交后重读重查；</li>
 *   <li><strong>写后校验</strong>：写完后 {@link UserDao#countActiveMetaPermissionHolders()}
 *       == 0 即抛异常回滚（朴素的前置检查挡不住「两个管理员并发互删」）。</li>
 * </ol>
 *
 * <p>覆盖路径：改用户角色、改用户状态、改角色（权限点 / 数据范围）、删角色、建角色。
 * 每个方法写后即时失效服务端缓存（当前用户 / 权限快照），并写审计
 * （操作人取 {@link CurrentUser} 真实登录态，clientIp / userAgent 随事件落库）。</p>
 */
@Service
public class RbacService {

    private static final Logger log = LoggerFactory.getLogger(RbacService.class);

    private final UserDao userDao;
    private final RoleDao roleDao;
    private final PermissionService permissionService;
    private final CurrentUserService currentUserService;
    private final AuditWriter auditWriter;
    private final ZoneId zone;

    public RbacService(UserDao userDao, RoleDao roleDao, PermissionService permissionService,
                       CurrentUserService currentUserService, AuditWriter auditWriter, ZoneId zone) {
        this.userDao = userDao;
        this.roleDao = roleDao;
        this.permissionService = permissionService;
        this.currentUserService = currentUserService;
        this.auditWriter = auditWriter;
        this.zone = zone;
    }

    // ------------------------------------------------------------------
    // 用户角色（rbac.manage：PUT /api/users/{id}/roles）
    // ------------------------------------------------------------------

    /** 整体替换某用户的角色（删旧 + 插新，原子）。 */
    @Transactional
    public void replaceUserRoles(long userId, List<String> roleCodes, AuditActor actor) {
        // 锁必须是事务里的第一条语句：MySQL RR 的快照在首个一致性读时建立，若先做
        // 普通 SELECT 再拿锁，写后校验会从陈旧快照读数，防自锁形同虚设。
        lockMetaPermissions();
        requireUserExists(userId);
        List<Long> roleIds = resolveRoleIds(roleCodes);
        roleDao.replaceUserRoles(userId, roleIds, actor.id());
        checkInvariant();
        currentUserService.invalidate(userId);
        audit(actor, AuditAction.ROLE_ASSIGN, AuditTargetType.USER, userId, null,
                "{\"roles\":" + jsonArray(roleCodes) + "}");
    }

    // ------------------------------------------------------------------
    // 用户状态（user.manage：PUT /api/users/{id}，status 非空时走这里）
    // ------------------------------------------------------------------

    /** 启停用用户（ACTIVE / DISABLED），走防自锁不变式。 */
    @Transactional
    public void updateUserStatus(long userId, UserStatus status, AuditActor actor) {
        lockMetaPermissions();
        requireUserExists(userId);
        userDao.updateStatus(userId, status);
        checkInvariant();
        currentUserService.invalidate(userId);
        audit(actor, AuditAction.USER_UPDATE, AuditTargetType.USER, userId, null,
                "{\"status\":\"" + status.getCode() + "\"}");
    }

    // ------------------------------------------------------------------
    // 角色 CRUD（rbac.manage）
    // ------------------------------------------------------------------

    /** 新建自定义角色（连同权限点与实例范围授权）。 */
    @Transactional
    public long createRole(String code, String name, String description, InstanceScope instanceScope,
                           List<Long> permissionIds, List<Long> instanceIds, AuditActor actor) {
        String trimmed = code == null ? "" : code.trim();
        if (trimmed.isEmpty()) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "角色 code 不能为空");
        }
        lockMetaPermissions();
        if (roleDao.findByCode(trimmed) != null) {
            throw new SqlauditException(ErrorCode.BUSINESS_ERROR, "角色 code 已存在: " + trimmed);
        }
        Role role = new Role();
        role.setCode(trimmed);
        role.setName(name == null || name.isBlank() ? trimmed : name.trim());
        role.setDescription(description);
        role.setBuiltin(false);
        role.setInstanceScope(instanceScope == null ? InstanceScope.SELECTED : instanceScope);
        long id = roleDao.insert(role);
        roleDao.replacePermissions(id, permissionIds == null ? List.of() : permissionIds, actor.id());
        roleDao.replaceInstances(id, instanceIds == null ? List.of() : instanceIds, actor.id());
        checkInvariant();
        afterRoleWrite();
        audit(actor, AuditAction.ROLE_CREATE, AuditTargetType.ROLE, id, null,
                "{\"code\":\"" + trimmed + "\"}");
        return id;
    }

    /**
     * 更新角色（名称 / 说明 / 数据范围 / 权限点 / 实例授权整体替换；code 与 builtin 不可改）。
     * 内置角色（builtin）的名称与授权仍可改，只有 code 与删除被禁。
     */
    @Transactional
    public void updateRole(long roleId, String name, String description, InstanceScope instanceScope,
                           List<Long> permissionIds, List<Long> instanceIds, AuditActor actor) {
        lockMetaPermissions();
        Role existing = requireRole(roleId);
        Role role = new Role();
        role.setId(roleId);
        role.setName(name == null || name.isBlank() ? existing.getName() : name.trim());
        role.setDescription(description);
        role.setInstanceScope(instanceScope == null
                ? (existing.getInstanceScope() == null ? InstanceScope.SELECTED : existing.getInstanceScope())
                : instanceScope);
        roleDao.update(role);
        roleDao.replacePermissions(roleId, permissionIds == null ? List.of() : permissionIds, actor.id());
        roleDao.replaceInstances(roleId, instanceIds == null ? List.of() : instanceIds, actor.id());
        checkInvariant();
        afterRoleWrite();
        audit(actor, AuditAction.ROLE_UPDATE, AuditTargetType.ROLE, roleId, null,
                "{\"code\":\"" + existing.getCode() + "\"}");
    }

    /** 删除角色（内置角色在控制器层先拦 400；此处仍兜底）。 */
    @Transactional
    public void deleteRole(long roleId, AuditActor actor) {
        lockMetaPermissions();
        Role existing = requireRole(roleId);
        if (existing.isBuiltin()) {
            throw new SqlauditException(ErrorCode.BUILTIN_ROLE_IMMUTABLE, "内置角色不可删除");
        }
        roleDao.delete(roleId);
        checkInvariant();
        afterRoleWrite();
        audit(actor, AuditAction.ROLE_DELETE, AuditTargetType.ROLE, roleId, null,
                "{\"code\":\"" + existing.getCode() + "\"}");
    }

    // ------------------------------------------------------------------
    // 权限收敛（§4.3）：对「持有 meta 权限点的用户」改密码 / 改资料 / 停用，
    // 操作者自己也必须持有 meta 权限点
    // ------------------------------------------------------------------

    /**
     * 操作者若要对「持有 meta 权限点的目标用户」做敏感操作（改密码 / 改资料 / 停用），
     * 自己必须持有 meta 权限点，否则 403。
     */
    public void requirePrivilegedActor(CurrentUser operator, long targetUserId) {
        if (operator.id() == targetUserId) {
            return; // 自己改自己不属于「收敛」场景
        }
        CurrentUser target = currentUserService.resolve(targetUserId);
        if (target == null || !permissionService.holdsMetaPermission(target)) {
            return; // 目标不是元权限持有者，普通管理操作即可
        }
        if (!permissionService.holdsMetaPermission(operator)) {
            throw new SqlauditException(ErrorCode.FORBIDDEN,
                    "目标用户持有角色与权限管理能力，仅持有同等能力的用户可操作");
        }
    }

    // ------------------------------------------------------------------
    // 私有工具
    // ------------------------------------------------------------------

    /**
     * 事务内、任何 RBAC 写之前：锁定全部 meta 权限点行，串行化并发 RBAC 写。
     *
     * <p><strong>必须是事务里的第一条语句</strong>：MySQL REPEATABLE READ 下，事务快照在
     * 首个<em>一致性读</em>时建立（FOR UPDATE 属锁定读、不建立快照）。若锁前先做普通
     * SELECT，并发事务的写后校验 COUNT 会从锁前建立的陈旧快照读数，两个并发移除都能
     * 「看见对方还在」而双双通过——锁必须在最前，写后 COUNT 成为首个一致性读，读到的
     * 才是锁串行化之后的最新提交态。</p>
     */
    private void lockMetaPermissions() {
        userDao.lockMetaPermissions();
    }

    /** 事务内、写之后：校验防自锁不变式，违反抛 409 整体回滚。 */
    private void checkInvariant() {
        if (userDao.countActiveMetaPermissionHolders() == 0) {
            throw new SqlauditException(ErrorCode.RBAC_LOCKOUT,
                    "该操作会使系统失去最后一个有效的角色与权限管理持有者，已拒绝");
        }
    }

    /** 角色权限 / 数据范围变更后的缓存失效。 */
    private void afterRoleWrite() {
        permissionService.reload();
        currentUserService.invalidateAll();
    }

    private void requireUserExists(long userId) {
        if (userDao.findById(userId) == null) {
            throw new SqlauditException(ErrorCode.USER_NOT_FOUND, "用户不存在: " + userId);
        }
    }

    private Role requireRole(long roleId) {
        Role role = roleDao.findById(roleId);
        if (role == null) {
            throw new SqlauditException(ErrorCode.ROLE_NOT_FOUND, "角色不存在: " + roleId);
        }
        return role;
    }

    /** 角色 code → 角色 id（未知角色 400）。 */
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
                throw new SqlauditException(ErrorCode.INVALID_REQUEST, "未知角色: " + code);
            }
            ids.add(id);
        }
        return ids;
    }

    private void audit(AuditActor actor, AuditAction action, AuditTargetType targetType,
                       long targetId, Long instanceId, String detail) {
        try {
            auditWriter.append(AuditEvents.base(actor)
                    .action(action)
                    .targetType(targetType)
                    .targetId(String.valueOf(targetId))
                    .instanceId(instanceId)
                    .detailPlaintext(detail)
                    .result(AuditResult.SUCCESS)
                    .traceId("rbac-" + targetType.getCode() + "-" + targetId)
                    .createdAt(OffsetDateTime.now(zone))
                    .build());
        } catch (Exception e) {
            log.warn("RBAC 写操作审计失败 action={} targetId={}: {}", action.getCode(), targetId, e.getMessage());
        }
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
}
