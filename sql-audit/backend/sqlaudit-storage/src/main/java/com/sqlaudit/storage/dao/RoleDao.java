package com.sqlaudit.storage.dao;

import com.sqlaudit.storage.model.Role;

import java.util.List;

/**
 * 角色存储（对应 t_role / t_user_role 表）。
 *
 * <p>角色为内置 4 类（DEV/LEAD/DBA/ADMIN），权限矩阵见设计文档 §8.2。
 * 用户-角色为多对多，授权人留痕。</p>
 */
public interface RoleDao {

    /** 某用户拥有的角色（登录后解析权限用）。 */
    List<Role> listByUserId(long userId);

    /** 全部角色（配置页展示 / 用户授权下拉）。 */
    List<Role> listAll();

    /** 删除用户全部角色关联，返回删除行数。 */
    int removeAllRoles(long userId);

    /** 给用户授予角色（授权人留痕；调用方需先 removeAllRoles 完成「重设」）。 */
    void assignRoles(long userId, List<Long> roleIds, Long grantedBy);

    /** 按 code 查角色；未找到返回 null。 */
    Role findByCode(String code);

    /** 插入角色（自定义角色），回填自增 id。 */
    long insert(Role role);

    /** 更新角色（name/description/rank），按 id；影响行数 0 = 不存在。 */
    int update(Role role);

    /** 删除角色（按 id）；影响行数 0 = 不存在。 */
    int delete(long id);
}
