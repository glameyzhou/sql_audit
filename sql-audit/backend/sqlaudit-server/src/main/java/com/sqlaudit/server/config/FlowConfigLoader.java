package com.sqlaudit.server.config;

import com.sqlaudit.rule.risk.ChainNode;
import com.sqlaudit.rule.risk.FlowConfig;
import com.sqlaudit.storage.dao.FlowNodeDao;
import com.sqlaudit.storage.dao.FlowRuleDao;
import com.sqlaudit.storage.model.FlowNode;
import com.sqlaudit.storage.model.FlowRule;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批流配置加载器：从 t_flow_rule + t_flow_node 读「全局 / 实例 × 风险等级」节点列表，
 * 重灌 {@link FlowConfig}（追加模式，决定 9）。
 *
 * <p>不再有硬编码默认链与角色权重：全部数据来自 V2__seed.sql 与界面配置，代码里没有任何
 * 角色码字面量。启动时加载一次，流程 / 节点 CRUD 后由写方调 {@link #reload()}。</p>
 */
@Component
public class FlowConfigLoader implements ApplicationRunner {

    private final FlowRuleDao flowRuleDao;
    private final FlowNodeDao flowNodeDao;
    private final FlowConfig flowConfig;

    public FlowConfigLoader(FlowRuleDao flowRuleDao, FlowNodeDao flowNodeDao, FlowConfig flowConfig) {
        this.flowRuleDao = flowRuleDao;
        this.flowNodeDao = flowNodeDao;
        this.flowConfig = flowConfig;
    }

    @Override
    public void run(ApplicationArguments args) {
        reload();
    }

    /** 从 DB 重载全部审批链（清空 + 重灌）。 */
    public synchronized void reload() {
        Map<Long, List<FlowNode>> nodesByRule = new HashMap<>();
        for (FlowNode node : flowNodeDao.listAll()) {
            nodesByRule.computeIfAbsent(node.getFlowRuleId(), k -> new ArrayList<>()).add(node);
        }
        flowConfig.clear();
        for (FlowRule rule : flowRuleDao.listAll()) {
            if (rule.getEnabled() != null && !rule.getEnabled()) {
                continue;
            }
            List<FlowNode> nodeRows = nodesByRule.getOrDefault(rule.getId(), List.of());
            List<ChainNode> nodes = nodeRows.stream()
                    .map(n -> new ChainNode(n.getNodeOrder(), n.getId(),
                            n.getApproverType(), n.getApproverRef(), n.getMode()))
                    .toList();
            if (rule.getInstanceId() == null) {
                flowConfig.putGlobal(rule.getRiskLevel(), nodes);
            } else {
                flowConfig.putInstance(rule.getInstanceId(), rule.getRiskLevel(), nodes);
            }
        }
    }
}
