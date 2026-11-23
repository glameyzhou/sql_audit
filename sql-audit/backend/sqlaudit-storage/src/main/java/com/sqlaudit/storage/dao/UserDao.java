package com.sqlaudit.storage.dao;

import com.sqlaudit.common.enums.UserStatus;
import com.sqlaudit.storage.model.User;
import java.util.List;

/**
 * 用户存储（对应 t_user 表）。
 *
 * <p>唯一写入方：sqlaudit-server。登录按 username 查询并校验 bcrypt 哈希。
 * <strong>没有删除用户的功能</strong>（无软删列），停用走 {@link #updateStatus}。</p>
 */
public interface UserDao {

    /** 按登录名查（登录主路径）。未找到返回 {@code null}。 */
    User findByUsername(String username);

    User findById(long id);

    /** 全部用户（管理页列表，按 id 升序）。 */
    List<User> listAll();

    /** 仅 ACTIVE 用户（人员下拉、审批人选人，按 id 升序）。 */
    List<User> listActive();

    /** 插入用户，回填自增 id。 */
    long insert(User user);

    /** 更新最后登录时间（登录成功后打点）。 */
    int updateLastLoginAt(long id, java.time.LocalDateTime at);

    /** 重置密码（bcrypt 哈希），并刷新 updated_at。 */
    int updatePassword(long id, String passwordHash);

    /** 启用/停用（{@link UserStatus#ACTIVE} / {@link UserStatus#DISABLED}），并刷新 updated_at。 */
    int updateStatus(long id, UserStatus status);

    /** 更新显示名/邮箱，并刷新 updated_at。 */
    int updateProfile(long id, String displayName, String email);

    /**
     * 防自锁不变式用：统计「经任意角色持有某个元权限点（{@code t_permission.meta}）」的
     * ACTIVE 用户数。
     *
     * <p>必须在 RBAC 写事务内「写之后」调用（先写后查），返回 0 即违反不变式，
     * 调用方整笔回滚并返回 {@code RBAC_LOCKOUT}（409）。朴素的前置检查挡不住
     * 「两个管理员并发互删」，串行化由调用方对元权限行加锁完成。</p>
     */
    int countActiveMetaPermissionHolders();

    /**
     * 防自锁串行化用：锁定全部元权限点行（{@code SELECT id FROM t_permission
     * WHERE meta = TRUE FOR UPDATE}），返回被锁的权限点 id。
     *
     * <p>必须在事务内、且在任何 RBAC 写操作<strong>之前</strong>调用：它把所有并发的
     * RBAC 写操作排成一条队，使「写后校验不变式」真正可靠。</p>
     */
    List<Long> lockMetaPermissions();
}
