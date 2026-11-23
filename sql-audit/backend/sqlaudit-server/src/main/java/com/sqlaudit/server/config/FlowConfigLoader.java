package com.sqlaudit.server.config;

import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.rule.risk.ApprovalChain;
import com.sqlaudit.rule.risk.FlowConfig;
import com.sqlaudit.storage.dao.FlowRuleDao;
import com.sqlaudit.storage.model.FlowRule;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批流配置加载器：从 t_flow_rule + t_flow_node 读链，重灌 {@link FlowConfig}，并注入
 * 自定义角色权重（见 sql-audit-rbac-权限可配置化设计 落地后续）。
 *
 * <p>仅在 JDBC 方言（mysql/postgres/h2）生效——memory 方言无库，保留 {@link FlowConfig}
 * 硬编码默认。启动时加载一次，flow CRUD 后由 ConfigController 调 {@link #reload()}。</p>
 */
@Component
@Profile({"mysql", "postgres", "h2"})
public class FlowConfigLoader implements ApplicationRunner {

    private final FlowRuleDao flowRuleDao;
    private final JdbcTemplate jdbc;
    private final FlowConfig flowConfig;

    public FlowConfigLoader(FlowRuleDao flowRuleDao, JdbcTemplate jdbc, FlowConfig flowConfig) {
        this.flowRuleDao = flowRuleDao;
        this.jdbc = jdbc;
        this.flowConfig = flowConfig;
    }

    @Override
    public void run(ApplicationArguments args) {
        reload();
    }

    /** 从 DB 重载审批链 + 角色权重；DB 无数据则保留硬编码默认（兜底）。 */
    public synchronized void reload() {
        List<FlowRule> rules = flowRuleDao.listAll();
        if (rules.isEmpty()) {
            loadRoleRanks();
            return;
        }
        flowConfig.clear();
        loadRoleRanks();
        Map<RiskLevel, ApprovalChain> globals = new EnumMap<>(RiskLevel.class);
        Map<Long, Map<RiskLevel, ApprovalChain>> overrides = new HashMap<>();
        for (FlowRule rule : rules) {
            if (rule.getEnabled() != null && !rule.getEnabled()) {
                continue;
            }
            List<String> nodes = loadNodes(rule.getId());
            boolean secondConfirm = rule.getRiskLevel() == RiskLevel.CRITICAL;
            ApprovalChain chain = new ApprovalChain(nodes, secondConfirm,
                    rule.getInstanceId() == null ? "L1_GLOBAL" : "L2_INSTANCE", rule.getRiskLevel());
            if (rule.getInstanceId() == null) {
                globals.put(rule.getRiskLevel(), chain);
            } else {
                overrides.computeIfAbsent(rule.getInstanceId(), k -> new EnumMap<>(RiskLevel.class))
                        .put(rule.getRiskLevel(), chain);
            }
        }
        globals.forEach(flowConfig::putGlobal);
        overrides.forEach((instanceId, map) ->
                map.forEach((level, chain) -> flowConfig.addInstanceOverride(instanceId, level, chain)));
    }

    /** 角色权重：内置默认 + 自定义（t_role.rank）。 */
    private void loadRoleRanks() {
        for (Map<String, Object> row : jdbc.queryForList("SELECT code, rank FROM t_role")) {
            Object code = row.get("code");
            Object rank = row.get("rank");
            if (code != null && rank != null) {
                flowConfig.putRoleRank((String) code, ((Number) rank).intValue());
            }
        }
    }

    /** 某 flow_rule 的节点角色 code 序列（按 node_order）；仅 ROLE 节点，USER/INSTANCE_OWNER 跳过。 */
    private List<String> loadNodes(long flowRuleId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT approver_type, approver_ref FROM t_flow_node WHERE flow_rule_id = ? ORDER BY node_order, id",
                flowRuleId);
        List<String> nodes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!"ROLE".equals(row.get("approver_type"))) {
                continue;
            }
            String ref = (String) row.get("approver_ref");
            if (ref != null && !ref.isBlank()) {
                nodes.add(ref);
            }
        }
        return nodes;
    }
}
