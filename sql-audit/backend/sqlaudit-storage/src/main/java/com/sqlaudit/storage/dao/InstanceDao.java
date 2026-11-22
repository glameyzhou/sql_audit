package com.sqlaudit.storage.dao;

import com.sqlaudit.storage.model.Instance;

import java.util.List;

/**
 * 实例存储（对应 t_instance 表）。
 *
 * <p>唯一写入方：sqlaudit-server（系统配置 CRUD）。删除为软删（置 deleted_at），
 * {@code listAll()} / {@code findById()} 只返回未删除记录。</p>
 */
public interface InstanceDao {

    /** 全部未删除实例，按 id 升序。 */
    List<Instance> listAll();

    /** 按 id 查（未找到或已软删返回 {@code null}）。 */
    Instance findById(long id);

    /** 插入实例，回填自增 id。 */
    long insert(Instance instance);

    /** 全量更新（按 id），影响行数 0 = 实例不存在或已软删。 */
    int update(Instance instance);

    /** 软删（置 deleted_at），影响行数 0 = 实例不存在或已软删。 */
    int delete(long id);
}
