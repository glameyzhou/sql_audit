package com.sqlaudit.server.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统配置只读端点（契约对齐 frontend/src/api/index.js 的 config 段）。
 *
 * <p>配置查询为简单只读，本轮直用 {@link JdbcTemplate} 查平台库（不建 DAO）；
 * 写入（CRUD）属 W2 后续。返回字段统一转驼峰，供前端按 camelCase 读取。</p>
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final JdbcTemplate jdbc;

    public ConfigController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 审核规则列表。 */
    @GetMapping("/rules")
    public List<Map<String, Object>> rules() {
        return toCamel(jdbc.queryForList("SELECT * FROM t_rule ORDER BY id"));
    }

    /** 审批流规则列表。 */
    @GetMapping("/flows")
    public List<Map<String, Object>> flows() {
        return toCamel(jdbc.queryForList("SELECT * FROM t_flow_rule ORDER BY id"));
    }

    /** 某条审批流规则的节点链。 */
    @GetMapping("/flows/{id}/nodes")
    public List<Map<String, Object>> flowNodes(@PathVariable long id) {
        return toCamel(jdbc.queryForList(
                "SELECT * FROM t_flow_node WHERE flow_rule_id = ? ORDER BY node_order, id", id));
    }

    /** 实例列表。 */
    @GetMapping("/instances")
    public List<Map<String, Object>> instances() {
        return toCamel(jdbc.queryForList("SELECT * FROM t_instance ORDER BY id"));
    }

    private static List<Map<String, Object>> toCamel(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            row.forEach((k, v) -> m.put(snakeToCamel(k), v));
            out.add(m);
        }
        return out;
    }

    private static String snakeToCamel(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean upper = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_') {
                upper = true;
                continue;
            }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }
}
