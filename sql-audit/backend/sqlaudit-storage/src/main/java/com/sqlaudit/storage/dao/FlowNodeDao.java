package com.sqlaudit.storage.dao;

import com.sqlaudit.storage.model.FlowNode;

import java.util.List;

/**
 * 审批节点存储（对应 t_flow_node 表）。
 *
 * <p>唯一写入方：sqlaudit-server。节点只能<strong>整体替换</strong>
 * （{@link #replaceNodes}，删旧 + 插新，原子）——按单条增删改很难保证
 * 「组内 mode 一致 + node_order 从 1 连续」这两个不变式。</p>
 */
public interface FlowNodeDao {

    /** 某流程的节点，按 (node_order, id) 稳定排序。 */
    List<FlowNode> listByFlowRule(long flowRuleId);

    /**
     * 全部节点（启动时一次性加载全部审批链），按 (flow_rule_id, node_order, id) 排序。
     * 节点总量是配置级（几十条），一次读完比按流程 N 次查省事。
     */
    List<FlowNode> listAll();

    /**
     * 整体替换某流程的节点（删旧 + 插新，单事务）；{@code nodes} 为空 = 该流程免审。
     * 节点的 {@code flowRuleId} 由本方法统一赋值，调用方不必填。
     *
     * @return 新插入的节点数
     */
    int replaceNodes(long flowRuleId, List<FlowNode> nodes);

    /** 删除某流程的全部节点（删流程时连带调用）；返回删除行数。 */
    int deleteByFlowRule(long flowRuleId);
}
