package com.sqlaudit.rule.risk;

import com.sqlaudit.common.enums.RiskLevel;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批流配置（内存模型，对应 flow_rule / flow_node）。
 *
 * <p>只保存<strong>从库加载</strong>的「全局 / 实例 × 风险等级」节点列表
 * （server 的 FlowConfigLoader 装配），不再有硬编码默认链与角色权重——
 * 数据全部来自 V2__seed.sql 与界面配置，代码里没有任何角色码字面量。</p>
 *
 * <ul>
 *   <li>全局链：按风险等级一条（实例漏配时落这里）；</li>
 *   <li>实例专用链：instanceId × 风险等级，{@link ApprovalRouter} 把它
 *       <strong>追加</strong>在全局链之后（只会更严，不可放宽）；</li>
 *   <li>LOW 空链 = 免审（自动通过留痕）；非 LOW 空链由 workflow 层拒绝提交。</li>
 * </ul>
 *
 * <p>本类是纯数据容器：判定都在 {@link ApprovalRouter}，不参与任何「取最严」比较。</p>
 */
public class FlowConfig {

    /** 全局链：风险等级 → 节点列表（同 nodeOrder 为一组）。 */
    private final Map<RiskLevel, List<ChainNode>> global = new EnumMap<>(RiskLevel.class);

    /** 实例专用链：instanceId → (风险等级 → 节点列表)。 */
    private final Map<Long, Map<RiskLevel, List<ChainNode>>> instanceChains = new HashMap<>();

    /** 覆盖某风险等级的全局链（DB 加载用）。 */
    public void putGlobal(RiskLevel level, List<ChainNode> nodes) {
        global.put(level, List.copyOf(nodes));
    }

    /** 登记某实例某风险等级的专用链（DB 加载用）。 */
    public void putInstance(long instanceId, RiskLevel level, List<ChainNode> nodes) {
        instanceChains.computeIfAbsent(instanceId, k -> new EnumMap<>(RiskLevel.class))
                .put(level, List.copyOf(nodes));
    }

    /** 清空全局链与实例链（DB 加载前调用，随后重灌）。 */
    public void clear() {
        global.clear();
        instanceChains.clear();
    }

    /** 全局链节点（无配置返回空列表）。 */
    public List<ChainNode> globalNodes(RiskLevel level) {
        return global.getOrDefault(level, List.of());
    }

    /** 实例专用链节点（无配置返回空列表）。 */
    public List<ChainNode> instanceNodes(long instanceId, RiskLevel level) {
        return instanceChains.getOrDefault(instanceId, Map.of()).getOrDefault(level, List.of());
    }
}
