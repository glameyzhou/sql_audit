package com.sqlaudit.storage.impl.jdbc.postgresql;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApproverType;
import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.storage.dao.FlowNodeDao;
import com.sqlaudit.storage.impl.jdbc.JdbcTx;
import com.sqlaudit.storage.model.FlowNode;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * flow_node 的 PostgreSQL 真实现（t_flow_node 表，SQL 逐句对照 MySQL 版
 * {@code MySqlFlowNodeDao}）。
 *
 * <p>{@link #replaceNodes} 在事务内「删旧 + 插新」：中途失败会让一条审批流只剩半条链，
 * 比没有链更危险（工单会卡在不存在的组上）。自增 id 回填用 {@code INSERT ... RETURNING id}。</p>
 */
public class PostgresFlowNodeDao implements FlowNodeDao {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PostgresFlowNodeDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.tx = JdbcTx.of(jdbc);
    }

    private static final RowMapper<FlowNode> MAPPER = (rs, i) -> {
        FlowNode n = new FlowNode();
        n.setId(rs.getLong("id"));
        n.setFlowRuleId(rs.getLong("flow_rule_id"));
        n.setNodeOrder(rs.getInt("node_order"));
        n.setApproverType(ApproverType.fromCode(rs.getString("approver_type")));
        n.setApproverRef(rs.getString("approver_ref"));
        n.setMode(ApprovalMode.fromCode(rs.getString("mode")));
        Timestamp created = rs.getTimestamp("created_at");
        n.setCreatedAt(created == null ? null : created.toLocalDateTime());
        return n;
    };

    @Override
    public List<FlowNode> listByFlowRule(long flowRuleId) {
        return jdbc.query(
                "SELECT * FROM t_flow_node WHERE flow_rule_id=? ORDER BY node_order, id",
                MAPPER, flowRuleId);
    }

    @Override
    public List<FlowNode> listAll() {
        return jdbc.query("SELECT * FROM t_flow_node ORDER BY flow_rule_id, node_order, id", MAPPER);
    }

    @Override
    public int replaceNodes(long flowRuleId, List<FlowNode> nodes) {
        Integer inserted = tx.execute(status -> {
            jdbc.update("DELETE FROM t_flow_node WHERE flow_rule_id=?", flowRuleId);
            int n = 0;
            for (FlowNode node : nodes) {
                node.setFlowRuleId(flowRuleId);
                n += jdbc.update(
                        "INSERT INTO t_flow_node (flow_rule_id, node_order, approver_type, approver_ref, "
                                + "mode, created_at) VALUES (?,?,?,?,?,?)",
                        flowRuleId, node.getNodeOrder(),
                        CodedEnum.code(node.getApproverType()), node.getApproverRef(),
                        CodedEnum.code(node.getMode()),
                        Timestamp.valueOf(node.getCreatedAt() != null ? node.getCreatedAt() : LocalDateTime.now()));
            }
            return n;
        });
        return inserted == null ? 0 : inserted;
    }

    @Override
    public int deleteByFlowRule(long flowRuleId) {
        return jdbc.update("DELETE FROM t_flow_node WHERE flow_rule_id=?", flowRuleId);
    }
}
