package com.sqlaudit.storage.impl.jdbc.h2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.common.enums.RuleDialect;
import com.sqlaudit.common.enums.RuleSeverity;
import com.sqlaudit.common.enums.RuleStatementType;
import com.sqlaudit.storage.dao.RuleDao;
import com.sqlaudit.storage.model.Rule;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * rule 的 H2 真实现（DDL §6 t_rule 表；内存版语义基准 {@code InMemoryRuleDao}，
 * SQL 逐句对照 PostgreSQL 版 {@code PostgresRuleDao}）。
 *
 * <p>H2 的 JSON 类型对普通字符串参数会按 JSON 字符串值存储（读出带引号），故
 * {@code params} 写入用 {@code ? FORMAT JSON} 解析为 JSON 结构；null 用 SQL NULL 字面量。
 * 自增 id 回填用 {@code GeneratedKeyHolder}（{@code new String[]{"id"}}）。删除为硬删。</p>
 */
public class H2RuleDao implements RuleDao {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public H2RuleDao(JdbcTemplate jdbc) {
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
        boolean hasParams = rule.getParams() != null;
        String sql = "INSERT INTO t_rule (code, dialect, statement_type, severity, enabled, params, "
                + "description, created_at) VALUES (?,?,?,?,?,"
                + (hasParams ? "? FORMAT JSON" : "NULL") + ",?,?)";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, rule.getCode());
            ps.setString(2, CodedEnum.code(rule.getDialect()));
            ps.setString(3, CodedEnum.code(rule.getStatementType()));
            ps.setString(4, CodedEnum.code(rule.getSeverity()));
            ps.setBoolean(5, rule.getEnabled() == null || rule.getEnabled());
            int i = 6;
            if (hasParams) {
                ps.setString(i++, writeParams(rule.getParams()));
            }
            ps.setString(i++, rule.getDescription());
            ps.setTimestamp(i, Timestamp.valueOf(rule.getCreatedAt() != null
                    ? rule.getCreatedAt() : LocalDateTime.now()));
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        rule.setId(id);
        return id;
    }

    @Override
    public int update(Rule rule) {
        boolean hasParams = rule.getParams() != null;
        String sql = "UPDATE t_rule SET code=?, dialect=?, statement_type=?, severity=?, enabled=?, params="
                + (hasParams ? "? FORMAT JSON" : "NULL") + ", description=? WHERE id=?";
        return jdbc.update(sql,
                rule.getCode(),
                CodedEnum.code(rule.getDialect()),
                CodedEnum.code(rule.getStatementType()),
                CodedEnum.code(rule.getSeverity()),
                rule.getEnabled() == null || rule.getEnabled(),
                hasParams ? writeParams(rule.getParams()) : null,
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
