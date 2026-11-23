package com.sqlaudit.storage.impl.jdbc.mysql;

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
 * rule 的 MySQL 真实现（t_rule 表）。
 *
 * <p>{@code params} 为 {@code TEXT} 列、模型即 JSON 文本（String），DAO
 * <strong>原样读写</strong>——不经过任何方言 JSON 类型，字节级透传（是否解析成 JSON 值由
 * 上层决定）。{@link #update} 刷新 {@code updated_at}。删除为硬删。</p>
 */
public class MySqlRuleDao implements RuleDao {

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
        r.setParams(rs.getString("params"));
        r.setDescription(rs.getString("description"));
        Timestamp created = rs.getTimestamp("created_at");
        r.setCreatedAt(created == null ? null : created.toLocalDateTime());
        Timestamp updated = rs.getTimestamp("updated_at");
        r.setUpdatedAt(updated == null ? null : updated.toLocalDateTime());
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
                            + "description, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, rule.getCode());
            ps.setString(2, CodedEnum.code(rule.getDialect()));
            ps.setString(3, CodedEnum.code(rule.getStatementType()));
            ps.setString(4, CodedEnum.code(rule.getSeverity()));
            ps.setBoolean(5, rule.getEnabled() == null || rule.getEnabled());
            ps.setString(6, rule.getParams());
            ps.setString(7, rule.getDescription());
            LocalDateTime now = LocalDateTime.now();
            ps.setTimestamp(8, Timestamp.valueOf(rule.getCreatedAt() != null
                    ? rule.getCreatedAt() : now));
            ps.setTimestamp(9, Timestamp.valueOf(rule.getUpdatedAt() != null
                    ? rule.getUpdatedAt() : now));
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
                        + "params=?, description=?, updated_at=? WHERE id=?",
                rule.getCode(),
                CodedEnum.code(rule.getDialect()),
                CodedEnum.code(rule.getStatementType()),
                CodedEnum.code(rule.getSeverity()),
                rule.getEnabled() == null || rule.getEnabled(),
                rule.getParams(),
                rule.getDescription(),
                Timestamp.valueOf(LocalDateTime.now()),
                rule.getId());
    }

    @Override
    public int delete(long id) {
        return jdbc.update("DELETE FROM t_rule WHERE id=?", id);
    }
}
