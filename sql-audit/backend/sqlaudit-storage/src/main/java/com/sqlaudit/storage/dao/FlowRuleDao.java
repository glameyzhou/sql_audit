package com.sqlaudit.storage.dao;

import com.sqlaudit.storage.model.FlowRule;

import java.util.List;

/**
 * 审批流规则存储（对应 t_flow_rule 表）。
 *
 * <p>唯一写入方：sqlaudit-workflow（本轮系统配置 CRUD 经 sqlaudit-server 写入）。
 * 删除为硬删。</p>
 */
public interface FlowRuleDao {

    /** 全部审批流规则，按 id 升序。 */
    List<FlowRule> listAll();

    /** 按 id 查（未找到返回 {@code null}）。 */
    FlowRule findById(long id);

    /** 插入审批流规则，回填自增 id。 */
    long insert(FlowRule flowRule);

    /** 全量更新（按 id），影响行数 0 = 规则不存在。 */
    int update(FlowRule flowRule);

    /** 硬删（按 id），影响行数 0 = 规则不存在。 */
    int delete(long id);
}
