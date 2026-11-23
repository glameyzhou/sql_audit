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
import java.util.List;

/**
 * 登录认证服务：用户名 → 校验 bcrypt 密码 → 签发 JWT（roles 取 RoleDao.listByUserId 的 code）。
 *
 * <p>密码错误 / 用户不存在 / 账号停用 → {@link AuthenticationException}（HTTP 401）。
 * 登录成功后打点 {@code last_login_at}。</p>
 */
@Service
public class AuthService {

    /** token 有效期：8 小时。 */
    private static final long TTL_SECONDS = 8 * 3600L;

    private final UserDao userDao;
    private final RoleDao roleDao;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(UserDao userDao, RoleDao roleDao, JwtService jwtService,
                       BCryptPasswordEncoder passwordEncoder) {
        this.userDao = userDao;
        this.roleDao = roleDao;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
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
        List<String> roles = roleDao.listByUserId(user.getId()).stream()
                .map(Role::getCode)
                .toList();
        String token = jwtService.issue(user.getId(), user.getUsername(), roles, TTL_SECONDS);
        userDao.updateLastLoginAt(user.getId(), LocalDateTime.now());
        return new LoginResult(token, user.getId(), user.getUsername(), user.getDisplayName(), roles);
    }
}
