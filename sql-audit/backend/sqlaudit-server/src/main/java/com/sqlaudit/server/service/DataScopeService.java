package com.sqlaudit.server.service;

import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.enums.InstanceScope;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.server.auth.RoleAuthInterceptor;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.model.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 实例数据范围（契约 §4.4）：功能权限回答「能不能调这个接口」，本服务回答「能对哪些实例调」。
 *
 * <p>允许的实例集合基于「授权本次请求的角色集合」（拦截器写入 request attribute）计算：
 * 任一授权角色 {@code instance_scope=ALL} → 全部实例；否则为这些角色
 * {@code t_role_instance} 的并集。角色集合为空（如 all_users 权限点放行的请求）→ 一个实例
 * 都不覆盖。</p>
 *
 * <p>生效入口（均在服务层，不在 URI 模式层表达数据域）：实例列表与库表元数据浏览、工单预览 /
 * 提交、线上查询、执行 / 回滚列表与明细、查看全部工单；越权 403
 * {@code INSTANCE_OUT_OF_SCOPE}。{@code /api/config/instances/**} 属管理功能不受数据范围限制。</p>
 */
@Service
public class DataScopeService {

    private final RoleDao roleDao;

    public DataScopeService(RoleDao roleDao) {
        this.roleDao = roleDao;
    }

    /**
     * 基于授权角色集合计算允许的实例集合。
     *
     * @return {@code all=true} 表示覆盖全部实例；否则为显式实例 id 集合
     */
    public Scope scopeOf(Set<Long> authorizingRoleIds) {
        if (authorizingRoleIds == null || authorizingRoleIds.isEmpty()) {
            return Scope.coveringNone();
        }
        Set<Long> ids = new HashSet<>();
        for (long roleId : authorizingRoleIds) {
            Role role = roleDao.findById(roleId);
            if (role == null) {
                continue;
            }
            if (role.getInstanceScope() == InstanceScope.ALL) {
                return Scope.coveringAll();
            }
            ids.addAll(roleDao.listInstanceIds(roleId));
        }
        return new Scope(false, Set.copyOf(ids));
    }

    /** 取当前请求的数据范围（授权角色集合来自拦截器 request attribute）。 */
    public Scope requestScope(HttpServletRequest request) {
        return scopeOf(RoleAuthInterceptor.authorizingRoles(request));
    }

    /** 校验实例在范围内，越权抛 {@code INSTANCE_OUT_OF_SCOPE}（403）。 */
    public void requireInScope(Scope scope, long instanceId) {
        if (!scope.covers(instanceId)) {
            throw new SqlauditException(ErrorCode.INSTANCE_OUT_OF_SCOPE,
                    "实例 " + instanceId + " 不在当前用户的数据范围内");
        }
    }

    /** 数据范围：覆盖全部（{@code all=true}）或显式实例 id 集合。 */
    public record Scope(boolean all, Set<Long> ids) {

        /** 覆盖全部实例。 */
        public static Scope coveringAll() {
            return new Scope(true, Set.of());
        }

        /** 一个实例都不覆盖。 */
        public static Scope coveringNone() {
            return new Scope(false, Set.of());
        }

        public boolean covers(long instanceId) {
            return all || ids.contains(instanceId);
        }
    }
}
