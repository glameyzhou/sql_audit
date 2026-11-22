package com.sqlaudit.storage.dao;

import com.sqlaudit.storage.model.Rule;

import java.util.List;

/**
 * 审核规则存储（对应 t_rule 表）。
 *
 * <p>唯一写入方：sqlaudit-rule（本轮系统配置 CRUD 经 sqlaudit-server 写入）。
 * 删除为硬删。</p>
 */
public interface RuleDao {

    /** 全部规则，按 id 升序。 */
    List<Rule> listAll();

    /** 插入规则，回填自增 id。 */
    long insert(Rule rule);

    /** 全量更新（按 id），影响行数 0 = 规则不存在。 */
    int update(Rule rule);

    /** 硬删（按 id），影响行数 0 = 规则不存在。 */
    int delete(long id);
}
