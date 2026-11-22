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
}
