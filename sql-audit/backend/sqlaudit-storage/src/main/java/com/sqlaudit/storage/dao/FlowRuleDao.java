package com.sqlaudit.storage.dao;

import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.storage.model.FlowRule;

import java.util.List;

/**
 * 审批流程存储（对应 t_flow_rule 表）。
 *
 * <p>唯一写入方：sqlaudit-server。「范围（全局 / 某实例）× 风险等级」至多一条：
 * 实例专用的唯一性由库级唯一约束保证，全局的（{@code instance_id} 为 NULL）由服务层用
 * {@link #findByScope} 校验（三方言的 UNIQUE 都不约束 NULL）。删除为硬删，连带节点。</p>
 */
public interface FlowRuleDao {

    /** 全部审批流规则，按 id 升序。 */
    List<FlowRule> listAll();

    /** 按 id 查（未找到返回 {@code null}）。 */
    FlowRule findById(long id);

    /**
     * 按「范围 + 风险等级」查（创建前判重）。
     *
     * @param instanceId {@code null} = 全局流程；非空 = 该实例的专用流程
     * @return 未找到返回 {@code null}
     */
    FlowRule findByScope(Long instanceId, RiskLevel riskLevel);

    /** 插入审批流规则，回填自增 id。 */
    long insert(FlowRule flowRule);

    /**
     * 更新 name / enabled 并刷新 updated_at（<strong>范围与风险等级创建后不可改</strong>——
     * 改了等于换了一条流程，历史审批记录会对不上）；影响行数 0 = 流程不存在。
     */
    int update(FlowRule flowRule);

    /** 硬删（按 id），<strong>连同该流程的全部节点</strong>（单事务）；影响行数 0 = 流程不存在。 */
    int delete(long id);
}
