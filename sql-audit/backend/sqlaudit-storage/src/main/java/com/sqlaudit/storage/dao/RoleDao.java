package com.sqlaudit.storage.dao;

import com.sqlaudit.storage.model.Role;

import java.util.List;

/**
 * 角色存储（对应 t_role / t_user_role / t_role_permission / t_role_instance 四表）。
 *
 * <p>唯一写入方：sqlaudit-server。角色、角色的功能权限、角色的实例数据范围都是可配置数据：
 * <strong>代码里不做角色码字面量判断</strong>，「超级管理员」只是一个持有全部权限点的普通角色。</p>
 *
 * <p>「整体替换」语义（{@link #replacePermissions} / {@link #replaceInstances} /
 * {@link #replaceUserRoles}）= 删旧 + 插新，<strong>实现必须是原子的</strong>
 * （单事务，可被外层 {@code @Transactional} 合并）——中途失败留下「零权限角色」会直接
 * 关掉一批人的功能。</p>
 */
public interface RoleDao {

    // ---- 角色本身 ----

    /** 全部角色，按 id 升序。 */
    List<Role> listAll();

    /** 按 id 查；未找到返回 {@code null}。 */
    Role findById(long id);

    /** 按 code 查；未找到返回 {@code null}。 */
    Role findByCode(String code);

    /** 插入角色，回填自增 id。 */
    long insert(Role role);

    /**
     * 更新角色的 name / description / instance_scope（<strong>不改 code、不改 builtin</strong>），
     * 并刷新 updated_at；影响行数 0 = 角色不存在。
     */
    int update(Role role);

    /**
     * 删除角色，连同它的用户关联、权限授权、实例授权（单事务）；影响行数 0 = 角色不存在。
     * 内置角色的拦截在服务层（返回 400），DAO 不判。
     */
    int delete(long id);

    // ---- 用户 ↔ 角色 ----

    /** 某用户拥有的角色（每请求解析当前用户用），按 role id 升序。 */
    List<Role> listByUserId(long userId);

    /** 某角色的成员数（角色列表展示 userCount）。 */
    int countUsers(long roleId);

    /**
     * 某角色是否存在「除 excludeUserId 之外」的 ACTIVE 成员——审批流节点保存与
     * 审批人解析都要它（ROLE 节点若只剩提交人自己，这一组就没人能审）。
     *
     * @param excludeUserId 要排除的用户 id；传 {@code null} 表示不排除任何人
     */
    boolean hasActiveMember(long roleId, Long excludeUserId);

    /** 整体替换某用户的角色（删旧 + 插新，原子）。{@code roleIds} 为空 = 清空该用户角色。 */
    void replaceUserRoles(long userId, List<Long> roleIds, Long grantedBy);

    // ---- 角色 ↔ 权限点 ----

    /** 某角色已授权的权限点 id，按 id 升序。 */
    List<Long> listPermissionIds(long roleId);

    /** 整体替换某角色的权限点授权（删旧 + 插新，原子）。空集合 = 该角色没有任何功能权限。 */
    void replacePermissions(long roleId, List<Long> permissionIds, Long grantedBy);

    // ---- 角色 ↔ 实例（数据范围） ----

    /** 某角色已授权的实例 id，按 id 升序（仅 instance_scope=SELECTED 时有意义）。 */
    List<Long> listInstanceIds(long roleId);

    /** 整体替换某角色的实例授权（删旧 + 插新，原子）。 */
    void replaceInstances(long roleId, List<Long> instanceIds, Long grantedBy);
}
