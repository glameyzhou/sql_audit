package com.sqlaudit.storage.impl.jdbc.mysql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.common.enums.RuleDialect;
import com.sqlaudit.common.enums.RuleSeverity;
import com.sqlaudit.common.enums.RuleStatementType;
import com.sqlaudit.storage.dao.RuleDao;
import com.sqlaudit.storage.model.Rule;
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
 * rule 的 MySQL 真实现（DDL §6 t_rule 表；内存版语义基准 {@code InMemoryRuleDao}）。
 *
 * <p>{@code params}（JSON）在 {@link Object} 与 JSON 文本间双向转换（Jackson），
 * 前端可直接读写 JSON 值。自增 id 回填用 {@code GeneratedKeyHolder}
 * （{@code Statement.RETURN_GENERATED_KEYS}）。删除为硬删。</p>
 */
public class MySqlRuleDao implements RuleDao {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public MySqlRuleDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Rule> MAPPER = (rs, i) -> {
        Rule r = new Rule();
        r.setId(rs.getLong("id"));
        r.setCode(rs.getString("code"));
        r.setDialect(RuleDialect.fromCode(rs.getString("dialect")));
        r.setStatementType(RuleStatementType.fromCode(rs.getString("statement_type")));
        r.setSeverity(RuleSeverity.fromCode(rs.getString("severity")));
        r.setEnabled(rs.getBoolean("enabled"));
        r.setParams(readParams(rs.getString("params")));
        r.setDescription(rs.getString("description"));
        Timestamp created = rs.getTimestamp("created_at");
        r.setCreatedAt(created == null ? null : created.toLocalDateTime());
        return r;
    };

    @Override
    public List<Rule> listAll() {
        return jdbc.query("SELECT * FROM t_rule ORDER BY id", MAPPER);
    }

    @Override
    public long insert(Rule rule) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_rule (code, dialect, statement_type, severity, enabled, params, "
                            + "description, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, rule.getCode());
            ps.setString(2, CodedEnum.code(rule.getDialect()));
            ps.setString(3, CodedEnum.code(rule.getStatementType()));
            ps.setString(4, CodedEnum.code(rule.getSeverity()));
            ps.setBoolean(5, rule.getEnabled() == null || rule.getEnabled());
            ps.setString(6, writeParams(rule.getParams()));
            ps.setString(7, rule.getDescription());
            ps.setTimestamp(8, Timestamp.valueOf(rule.getCreatedAt() != null
                    ? rule.getCreatedAt() : LocalDateTime.now()));
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        rule.setId(id);
        return id;
    }

    @Override
    public int update(Rule rule) {
        return jdbc.update(
                "UPDATE t_rule SET code=?, dialect=?, statement_type=?, severity=?, enabled=?, "
                        + "params=?, description=? WHERE id=?",
                rule.getCode(),
                CodedEnum.code(rule.getDialect()),
                CodedEnum.code(rule.getStatementType()),
                CodedEnum.code(rule.getSeverity()),
                rule.getEnabled() == null || rule.getEnabled(),
                writeParams(rule.getParams()),
                rule.getDescription(),
                rule.getId());
    }

    @Override
    public int delete(long id) {
        return jdbc.update("DELETE FROM t_rule WHERE id=?", id);
    }

    private static String writeParams(Object params) {
        if (params == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(params);
        } catch (Exception e) {
            throw new IllegalStateException("params JSON 序列化失败", e);
        }
    }

    private static Object readParams(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return JSON.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("params JSON 解析失败: " + json, e);
        }
    }
}
