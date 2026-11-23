package com.sqlaudit.storage.dao;

import com.sqlaudit.common.enums.UserStatus;
import com.sqlaudit.storage.model.User;
import java.util.List;

/**
 * 用户存储（对应 t_user 表）。
 *
 * <p>唯一写入方：sqlaudit-auth。登录按 username 查询；本地账号校验 bcrypt 哈希，
 * 企微账号校验 openid 映射。</p>
 */
public interface UserDao {

    /** 按登录名查（登录主路径）。未找到返回 {@code null}。 */
    User findByUsername(String username);

    User findById(long id);

    /** 全部未删除用户（管理页列表，按 id 升序）。 */
    List<User> listAll();

    /** 插入用户，回填自增 id。 */
    long insert(User user);

    /** 更新最后登录时间（登录成功后打点）。 */
    int updateLastLoginAt(long id, java.time.LocalDateTime at);

    /** 重置本地账号密码（bcrypt 哈希），并刷新 updated_at。 */
    int updatePassword(long id, String passwordHash);

    /** 启用/停用（{@link UserStatus#ACTIVE} / {@link UserStatus#DISABLED}），并刷新 updated_at。 */
    int updateStatus(long id, UserStatus status);

    /** 更新显示名/邮箱，并刷新 updated_at。 */
    int updateProfile(long id, String displayName, String email);
}
