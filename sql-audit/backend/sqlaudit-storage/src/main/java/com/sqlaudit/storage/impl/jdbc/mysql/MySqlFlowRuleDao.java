package com.sqlaudit.storage.impl.jdbc.mysql;

import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.common.enums.Environment;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.storage.dao.FlowRuleDao;
import com.sqlaudit.storage.model.FlowRule;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * flow_rule 的 MySQL 真实现（DDL §5 t_flow_rule 表；内存版语义基准
 * {@code InMemoryFlowRuleDao}）。删除为硬删；自增 id 回填用
 * {@code GeneratedKeyHolder}（{@code Statement.RETURN_GENERATED_KEYS}）。
 */
public class MySqlFlowRuleDao implements FlowRuleDao {

    private final JdbcTemplate jdbc;

    public MySqlFlowRuleDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FlowRule> MAPPER = (rs, i) -> {
        FlowRule f = new FlowRule();
        f.setId(rs.getLong("id"));
        f.setName(rs.getString("name"));
        f.setEnvironment(Environment.fromCode(rs.getString("environment")));
        long instanceId = rs.getLong("instance_id");
        f.setInstanceId(rs.wasNull() ? null : instanceId);
        f.setRiskLevel(RiskLevel.fromCode(rs.getString("risk_level")));
        f.setNonOverridable(rs.getBoolean("non_overridable"));
        int priority = rs.getInt("priority");
        f.setPriority(rs.wasNull() ? null : priority);
        f.setEnabled(rs.getBoolean("enabled"));
        long createdBy = rs.getLong("created_by");
        f.setCreatedBy(rs.wasNull() ? null : createdBy);
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
    public long insert(FlowRule flowRule) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_flow_rule (name, environment, instance_id, risk_level, "
                            + "non_overridable, priority, enabled, created_by, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, flowRule.getName());
            ps.setString(2, CodedEnum.code(flowRule.getEnvironment()));
            setNullableLong(ps, 3, flowRule.getInstanceId());
            ps.setString(4, CodedEnum.code(flowRule.getRiskLevel()));
            ps.setBoolean(5, flowRule.getNonOverridable() != null && flowRule.getNonOverridable());
            ps.setInt(6, flowRule.getPriority() != null ? flowRule.getPriority() : 0);
            ps.setBoolean(7, flowRule.getEnabled() == null || flowRule.getEnabled());
            ps.setLong(8, flowRule.getCreatedBy());
            ps.setTimestamp(9, Timestamp.valueOf(flowRule.getCreatedAt() != null
                    ? flowRule.getCreatedAt() : LocalDateTime.now()));
            ps.setTimestamp(10, Timestamp.valueOf(flowRule.getUpdatedAt() != null
                    ? flowRule.getUpdatedAt() : LocalDateTime.now()));
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        flowRule.setId(id);
        return id;
    }

    @Override
    public int update(FlowRule flowRule) {
        return jdbc.update(
                "UPDATE t_flow_rule SET name=?, environment=?, instance_id=?, risk_level=?, "
                        + "non_overridable=?, priority=?, enabled=?, updated_at=? WHERE id=?",
                flowRule.getName(),
                CodedEnum.code(flowRule.getEnvironment()),
                flowRule.getInstanceId(),
                CodedEnum.code(flowRule.getRiskLevel()),
                flowRule.getNonOverridable() != null && flowRule.getNonOverridable(),
                flowRule.getPriority() != null ? flowRule.getPriority() : 0,
                flowRule.getEnabled() == null || flowRule.getEnabled(),
                Timestamp.valueOf(LocalDateTime.now()),
                flowRule.getId());
    }

    @Override
    public int delete(long id) {
        return jdbc.update("DELETE FROM t_flow_rule WHERE id=?", id);
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }
}
