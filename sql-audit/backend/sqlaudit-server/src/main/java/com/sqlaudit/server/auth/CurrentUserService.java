package com.sqlaudit.server.auth;

import com.sqlaudit.common.enums.UserStatus;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.model.Role;
import com.sqlaudit.storage.model.User;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 当前用户解析（每请求从库读，进程内短 TTL 缓存 + 写路径即时失效）。
 *
 * <p>为什么每请求查库而不是信 JWT：JWT 里的角色是签发时刻的快照，停用 / 改角色要等
 * token 过期才生效；对会往生产库跑 DDL 的平台不可接受。缓存只加速读取，
 * <strong>权威永远是库</strong>：</p>
 * <ul>
 *   <li>命中缓存的条目不超过 {@link #TTL_MILLIS}（30s），过期自动回库；</li>
 *   <li>写路径（用户状态 / 用户角色变更 → {@link #invalidate}；角色权限 /
 *       角色实例范围变更 → {@link #invalidateAll}）即时失效对应条目。</li>
 * </ul>
 */
@Service
public class CurrentUserService {

    /** 缓存 TTL：30 秒（契约 §4.2 的「短 TTL 兜底」）。 */
    private static final long TTL_MILLIS = 30_000L;

    private final UserDao userDao;
    private final RoleDao roleDao;

    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();

    public CurrentUserService(UserDao userDao, RoleDao roleDao) {
        this.userDao = userDao;
        this.roleDao = roleDao;
    }

    /**
     * 解析当前用户。
     *
     * @return 用户不存在返回 {@code null}；存在但停用仍返回（status 由调用方判 401）
     */
    public CurrentUser resolve(long userId) {
        Entry entry = cache.get(userId);
        if (entry != null && System.currentTimeMillis() - entry.loadedAtMillis() < TTL_MILLIS) {
            return entry.user();
        }
        User user = userDao.findById(userId);
        if (user == null) {
            return null;
        }
        List<Role> roles = roleDao.listByUserId(userId);
        List<Long> roleIds = new ArrayList<>(roles.size());
        List<String> roleCodes = new ArrayList<>(roles.size());
        for (Role role : roles) {
            roleIds.add(role.getId());
            roleCodes.add(role.getCode());
        }
        CurrentUser current = new CurrentUser(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getStatus() == null ? UserStatus.DISABLED : user.getStatus(),
                List.copyOf(roleIds), List.copyOf(roleCodes));
        cache.put(userId, new Entry(current, System.currentTimeMillis()));
        return current;
    }

    /** 写路径即时失效：用户状态 / 用户角色变更。 */
    public void invalidate(long userId) {
        cache.remove(userId);
    }

    /** 写路径即时失效：角色权限 / 角色实例范围变更（影响面不可枚举，全量失效）。 */
    public void invalidateAll() {
        cache.clear();
    }

    private record Entry(CurrentUser user, long loadedAtMillis) {
    }
}
