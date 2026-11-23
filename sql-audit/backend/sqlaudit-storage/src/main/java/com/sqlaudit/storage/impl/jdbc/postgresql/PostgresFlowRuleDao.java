package com.sqlaudit.storage.impl.jdbc.postgresql;

import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.storage.dao.FlowRuleDao;
import com.sqlaudit.storage.impl.jdbc.JdbcTx;
import com.sqlaudit.storage.model.FlowRule;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * flow_rule 的 PostgreSQL 真实现（t_flow_rule 表，SQL 逐句对照 MySQL 版
 * {@code MySqlFlowRuleDao}）。
 *
 * <p>{@link #findByScope} 供服务层判重：实例专用流程的唯一性由库级唯一约束
 * {@code uk_flow_scope_risk(instance_id, risk_level)} 保证，全局流程（{@code instance_id}
 * 为 NULL）的 UNIQUE 不约束 NULL，只能由服务层查后创建（违反返回 {@code DUPLICATE_FLOW}）。</p>
 *
 * <p>{@link #delete} 在事务内<strong>连同该流程的全部节点</strong>一起删
 * （{@code t_flow_node.flow_rule_id} 无物理外键，两步必须原子）。
 * PG 无 {@code ON UPDATE}：{@code updated_at} 由 DAO 显式写。</p>
 */
public class PostgresFlowRuleDao implements FlowRuleDao {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PostgresFlowRuleDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.tx = JdbcTx.of(jdbc);
    }

    private static final RowMapper<FlowRule> MAPPER = (rs, i) -> {
        FlowRule f = new FlowRule();
        f.setId(rs.getLong("id"));
        f.setName(rs.getString("name"));
        f.setInstanceId(rs.getObject("instance_id", Long.class));
        f.setRiskLevel(RiskLevel.fromCode(rs.getString("risk_level")));
        f.setEnabled(rs.getBoolean("enabled"));
        f.setCreatedBy(rs.getObject("created_by", Long.class));
        Timestamp created = rs.getTimestamp("created_at");
        f.setCreatedAt(created == null ? null : created.toLocalDateTime());
        Timestamp updated = rs.getTimestamp("updated_at");
        f.setUpdatedAt(updated == null ? null : updated.toLocalDateTime());
        return f;
    };

    @Override
    public List<FlowRule> listAll() {
        return jdbc.query("SELECT * FROM t_flow_rule ORDER BY id", MAPPER);
    }

    @Override
    public FlowRule findById(long id) {
        List<FlowRule> result = jdbc.query("SELECT * FROM t_flow_rule WHERE id=?", MAPPER, id);
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public FlowRule findByScope(Long instanceId, RiskLevel riskLevel) {
        // instance_id = NULL 的流程不能用「WHERE instance_id=NULL」匹配（恒为 UNKNOWN），
        // 必须按 IS NULL 分支查询
        List<FlowRule> result = instanceId == null
                ? jdbc.query("SELECT * FROM t_flow_rule WHERE instance_id IS NULL AND risk_level=?",
                        MAPPER, CodedEnum.code(riskLevel))
                : jdbc.query("SELECT * FROM t_flow_rule WHERE instance_id=? AND risk_level=?",
                        MAPPER, instanceId, CodedEnum.code(riskLevel));
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public long insert(FlowRule flowRule) {
        long id = jdbc.query(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_flow_rule (name, instance_id, risk_level, enabled, created_by, "
                            + "created_at, updated_at) VALUES (?,?,?,?,?,?,?) RETURNING id");
            ps.setString(1, flowRule.getName());
            if (flowRule.getInstanceId() == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, flowRule.getInstanceId());
            }
            ps.setString(3, CodedEnum.code(flowRule.getRiskLevel()));
            ps.setBoolean(4, flowRule.getEnabled() == null || flowRule.getEnabled());
            if (flowRule.getCreatedBy() == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, flowRule.getCreatedBy());
            }
            LocalDateTime now = LocalDateTime.now();
            ps.setTimestamp(6, Timestamp.valueOf(flowRule.getCreatedAt() != null
                    ? flowRule.getCreatedAt() : now));
            ps.setTimestamp(7, Timestamp.valueOf(flowRule.getUpdatedAt() != null
                    ? flowRule.getUpdatedAt() : now));
            return ps;
        }, rs -> {
            if (rs.next()) {
                return rs.getLong(1);
            }
            throw new IllegalStateException("INSERT ... RETURNING id 未返回任何行");
        });
        flowRule.setId(id);
        return id;
    }

    @Override
    public int update(FlowRule flowRule) {
        // 刻意只更新 name / enabled：范围与风险等级创建后不可改（改了等于换一条流程，
        // 历史审批记录的 node_order 就对不上了）
        return jdbc.update(
                "UPDATE t_flow_rule SET name=?, enabled=?, updated_at=? WHERE id=?",
                flowRule.getName(),
                flowRule.getEnabled() == null || flowRule.getEnabled(),
                Timestamp.valueOf(LocalDateTime.now()),
                flowRule.getId());
    }

    @Override
    public int delete(long id) {
        Integer affected = tx.execute(status -> {
            jdbc.update("DELETE FROM t_flow_node WHERE flow_rule_id=?", id);
            return jdbc.update("DELETE FROM t_flow_rule WHERE id=?", id);
        });
        return affected == null ? 0 : affected;
    }
}
