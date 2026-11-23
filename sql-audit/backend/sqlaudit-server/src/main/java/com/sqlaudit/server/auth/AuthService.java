package com.sqlaudit.server.auth;

import com.sqlaudit.auth.JwtService;
import com.sqlaudit.common.enums.UserStatus;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.model.Role;
import com.sqlaudit.storage.model.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 登录认证服务：用户名 → 校验 bcrypt 密码 → 签发 JWT。
 *
 * <p><strong>JWT 只装身份（userId）</strong>：签发时不再把角色写进 token——
 * 角色与状态每请求从库解析（{@link CurrentUserService}），停用 / 改角色即时生效。
 * 密码错误 / 用户不存在 / 账号停用 → {@link AuthenticationException}（HTTP 401）。
 * 登录成功后打点 {@code last_login_at}。</p>
 */
@Service
public class AuthService {

    /** token 有效期：8 小时（与改造前一致）。 */
    private static final long TTL_SECONDS = 8 * 3600L;

    private final UserDao userDao;
    private final RoleDao roleDao;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final PermissionService permissionService;
    private final CurrentUserService currentUserService;

    public AuthService(UserDao userDao, RoleDao roleDao, JwtService jwtService,
                       BCryptPasswordEncoder passwordEncoder, PermissionService permissionService,
                       CurrentUserService currentUserService) {
        this.userDao = userDao;
        this.roleDao = roleDao;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.permissionService = permissionService;
        this.currentUserService = currentUserService;
    }

    public LoginResult login(String username, String password) {
        User user = userDao.findByUsername(username);
        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new AuthenticationException("用户名或密码错误");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AuthenticationException("账号已停用");
        }
        // JWT 只装身份（roles 传空列表，保持 token 里无角色快照；鉴权一律从库解析）
        String token = jwtService.issue(user.getId(), user.getUsername(), List.of(), TTL_SECONDS);
        userDao.updateLastLoginAt(user.getId(), LocalDateTime.now());
        return new LoginResult(token, viewOf(user));
    }

    /** 构造当前用户视图（登录与 /api/auth/me 共用）。 */
    public UserView viewOf(CurrentUser user) {
        List<UserView.RoleRef> roles = new ArrayList<>();
        for (Role role : roleDao.listByUserId(user.id())) {
            roles.add(new UserView.RoleRef(role.getCode(), role.getName()));
        }
        return new UserView(user.id(), user.username(), user.displayName(),
                roles, permissionService.listApis(user));
    }

    private UserView viewOf(User user) {
        CurrentUser current = currentUserService.resolve(user.getId());
        if (current == null) {
            current = new CurrentUser(user.getId(), user.getUsername(), user.getDisplayName(),
                    user.getStatus() == null ? UserStatus.DISABLED : user.getStatus(),
                    List.of(), List.of());
        }
        return viewOf(current);
    }
}
