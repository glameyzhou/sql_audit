package com.sqlaudit.server.auth;

import com.sqlaudit.common.enums.RoleCode;
import com.sqlaudit.common.enums.UserSource;
import com.sqlaudit.common.enums.UserStatus;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.impl.memory.InMemoryRoleDao;
import com.sqlaudit.storage.model.User;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地应急账号种子：启动时若无 {@code admin}/{@code dev} 则创建（bcrypt 密码）。
 *
 * <p>admin/admin12345 → ADMIN+LEAD+DBA+DEV（全角色超级管理员）；dev/dev12345 → DEV。
 * 角色写入：JDBC 方言直插 t_user_role，内存方言走 {@link InMemoryRoleDao#grantByCode}
 * （接口未暴露授权写路径）；两处的角色 code 均取 {@link RoleCode#getCode()}。</p>
 */
@Component
public class SeedUserRunner implements ApplicationRunner {

    private final UserDao userDao;
    private final RoleDao roleDao;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;

    public SeedUserRunner(UserDao userDao, RoleDao roleDao, BCryptPasswordEncoder passwordEncoder,
                          JdbcTemplate jdbc) {
        this.userDao = userDao;
        this.roleDao = roleDao;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed("admin", "admin12345", List.of(RoleCode.ADMIN, RoleCode.LEAD, RoleCode.DBA, RoleCode.DEV));
        seed("dev", "dev12345", List.of(RoleCode.DEV));
    }

    private void seed(String username, String password, List<RoleCode> roleCodes) {
        if (userDao.findByUsername(username) != null) {
            return;
        }
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setSource(UserSource.LOCAL);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setStatus(UserStatus.ACTIVE);
        long userId = userDao.insert(user);
        for (RoleCode role : roleCodes) {
            grantRole(userId, role);
        }
    }

    private void grantRole(long userId, RoleCode role) {
        String roleCode = role.getCode(); // t_role.code 仍是字符串列
        if (roleDao instanceof InMemoryRoleDao memory) {
            memory.grantByCode(userId, roleCode);
        } else {
            jdbc.update(
                    "INSERT INTO t_user_role (user_id, role_id, granted_by) "
                            + "SELECT ?, id, NULL FROM t_role WHERE code = ?",
                    userId, roleCode);
        }
    }
}
