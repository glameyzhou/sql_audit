package com.sqlaudit.server.web;

import com.sqlaudit.audit.AuditWriter;
import com.sqlaudit.common.model.AuditEvent;
import com.sqlaudit.server.auth.RequireRole;
import com.sqlaudit.server.auth.RoleAuthInterceptor;
import com.sqlaudit.storage.dao.FlowRuleDao;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.dao.RuleDao;
import com.sqlaudit.storage.model.FlowRule;
import com.sqlaudit.storage.model.Instance;
import com.sqlaudit.storage.model.Rule;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统配置端点（契约对齐 frontend/src/api/index.js 的 config 段）。
 *
 * <p>读端点走 DAO（{@link InstanceDao} / {@link RuleDao} / {@link FlowRuleDao}），
 * 与写端点在 memory / mysql / postgres / h2 四套方言下语义一致；审批流节点链仍为
 * 只读直查（无对应 DAO）。写操作（POST/PUT/DELETE）受 RBAC 保护并逐条写审计
 * （{@link AuditWriter}，失败仅 warn 不阻断写入）。</p>
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    private final InstanceDao instanceDao;
    private final RuleDao ruleDao;
    private final FlowRuleDao flowRuleDao;
    private final JdbcTemplate jdbc;
    private final AuditWriter auditWriter;
    private final ZoneId zone;

    public ConfigController(InstanceDao instanceDao, RuleDao ruleDao, FlowRuleDao flowRuleDao,
                            JdbcTemplate jdbc, AuditWriter auditWriter, ZoneId zone) {
        this.instanceDao = instanceDao;
        this.ruleDao = ruleDao;
        this.flowRuleDao = flowRuleDao;
        this.jdbc = jdbc;
        this.auditWriter = auditWriter;
        this.zone = zone;
    }

    // ---------------- 读 ----------------

    /** 审核规则列表。 */
    @GetMapping("/rules")
    public List<Rule> rules() {
        return ruleDao.listAll();
    }

    /** 审批流规则列表。 */
    @GetMapping("/flows")
    public List<FlowRule> flows() {
        return flowRuleDao.listAll();
    }

    /** 某条审批流规则的节点链。 */
    @GetMapping("/flows/{id}/nodes")
    public List<Map<String, Object>> flowNodes(@PathVariable long id) {
        return toCamel(jdbc.queryForList(
                "SELECT * FROM t_flow_node WHERE flow_rule_id = ? ORDER BY node_order, id", id));
    }

    /** 实例列表（仅 ADMIN，用于验证 RBAC 拦截器）。 */
    @GetMapping("/instances")
    @RequireRole("ADMIN")
    public List<Instance> instances() {
        return instanceDao.listAll();
    }

    // ---------------- 实例 CRUD（ADMIN） ----------------

    @PostMapping("/instances")
    @RequireRole("ADMIN")
    public Map<String, Object> createInstance(@RequestBody Instance instance, HttpServletRequest request) {
        // 前端不再暴露 Owner ID：未显式指定时以当前登录用户为 Owner（自动进入该实例高危审批链）；
        // @RequestBody 显式携带 ownerId 时仍尊重显式值
        if (instance.getOwnerId() == null) {
            instance.setOwnerId(currentUserId(request));
        }
        instance.setCreatedBy(currentUserId(request));
        long id = instanceDao.insert(instance);
        audit(request, "INSTANCE_CREATE", "instance", id, id, "{\"name\":\"" + instance.getName() + "\"}");
        return Map.of("id", id);
    }

    @PutMapping("/instances/{id}")
    @RequireRole("ADMIN")
    public ResponseEntity<Map<String, Object>> updateInstance(@PathVariable long id,
                                                              @RequestBody Instance instance,
                                                              HttpServletRequest request) {
        instance.setId(id);
        int rows = instanceDao.update(instance);
        if (rows == 0) {
            return ResponseEntity.notFound().build();
        }
        audit(request, "INSTANCE_UPDATE", "instance", id, id, "{\"name\":\"" + instance.getName() + "\"}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    @DeleteMapping("/instances/{id}")
    @RequireRole("ADMIN")
    public ResponseEntity<Map<String, Object>> deleteInstance(@PathVariable long id, HttpServletRequest request) {
        int rows = instanceDao.delete(id);
        if (rows == 0) {
            return ResponseEntity.notFound().build();
        }
        audit(request, "INSTANCE_DELETE", "instance", id, id, "{\"id\":" + id + "}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    // ---------------- 规则 CRUD（ADMIN/DBA） ----------------

    @PostMapping("/rules")
    @RequireRole({"ADMIN", "DBA"})
    public Map<String, Object> createRule(@RequestBody Rule rule, HttpServletRequest request) {
        long id = ruleDao.insert(rule);
        audit(request, "RULE_CREATE", "rule", id, null, "{\"code\":\"" + rule.getCode() + "\"}");
        return Map.of("id", id);
    }

    @PutMapping("/rules/{id}")
    @RequireRole({"ADMIN", "DBA"})
    public ResponseEntity<Map<String, Object>> updateRule(@PathVariable long id,
                                                          @RequestBody Rule rule,
                                                          HttpServletRequest request) {
        rule.setId(id);
        int rows = ruleDao.update(rule);
        if (rows == 0) {
            return ResponseEntity.notFound().build();
        }
        audit(request, "RULE_UPDATE", "rule", id, null, "{\"code\":\"" + rule.getCode() + "\"}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    @DeleteMapping("/rules/{id}")
    @RequireRole({"ADMIN", "DBA"})
    public ResponseEntity<Map<String, Object>> deleteRule(@PathVariable long id, HttpServletRequest request) {
        int rows = ruleDao.delete(id);
        if (rows == 0) {
            return ResponseEntity.notFound().build();
        }
        audit(request, "RULE_DELETE", "rule", id, null, "{\"id\":" + id + "}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    // ---------------- 审批流 CRUD（ADMIN/DBA） ----------------

    @PostMapping("/flows")
    @RequireRole({"ADMIN", "DBA"})
    public Map<String, Object> createFlow(@RequestBody FlowRule flowRule, HttpServletRequest request) {
        flowRule.setCreatedBy(currentUserId(request));
        long id = flowRuleDao.insert(flowRule);
        audit(request, "FLOW_CREATE", "flow_rule", id, flowRule.getInstanceId(),
                "{\"name\":\"" + flowRule.getName() + "\"}");
        return Map.of("id", id);
    }

    @PutMapping("/flows/{id}")
    @RequireRole({"ADMIN", "DBA"})
    public ResponseEntity<Map<String, Object>> updateFlow(@PathVariable long id,
                                                          @RequestBody FlowRule flowRule,
                                                          HttpServletRequest request) {
        flowRule.setId(id);
        int rows = flowRuleDao.update(flowRule);
        if (rows == 0) {
            return ResponseEntity.notFound().build();
        }
        audit(request, "FLOW_UPDATE", "flow_rule", id, flowRule.getInstanceId(),
                "{\"name\":\"" + flowRule.getName() + "\"}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    @DeleteMapping("/flows/{id}")
    @RequireRole({"ADMIN", "DBA"})
    public ResponseEntity<Map<String, Object>> deleteFlow(@PathVariable long id, HttpServletRequest request) {
        int rows = flowRuleDao.delete(id);
        if (rows == 0) {
            return ResponseEntity.notFound().build();
        }
        audit(request, "FLOW_DELETE", "flow_rule", id, null, "{\"id\":" + id + "}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    // ---------------- 审计与工具 ----------------

    private void audit(HttpServletRequest request, String action, String targetType, long targetId,
                       Long instanceId, String detail) {
        try {
            Long actorId = (Long) request.getAttribute(RoleAuthInterceptor.ATTR_USER_ID);
            String actorName = (String) request.getAttribute(RoleAuthInterceptor.ATTR_USERNAME);
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) request.getAttribute(RoleAuthInterceptor.ATTR_ROLES);
            String actorRole = roles != null && !roles.isEmpty() ? roles.get(0) : null;
            AuditEvent event = AuditEvent.builder()
                    .actorId(actorId == null ? 0L : actorId)
                    .actorName(actorName == null ? "system" : actorName)
                    .actorRole(actorRole == null ? "SYSTEM" : actorRole)
                    .action(action)
                    .targetType(targetType)
                    .targetId(String.valueOf(targetId))
                    .instanceId(instanceId)
                    .detailPlaintext(detail)
                    .result("SUCCESS")
                    // audit_log.trace_id NOT NULL：配置操作链路标识，与 ticket-/rollback- 前缀口径一致
                    .traceId("config-" + targetType + "-" + targetId)
                    .createdAt(OffsetDateTime.now(zone))
                    .build();
            auditWriter.append(event);
        } catch (Exception e) {
            log.warn("配置写操作审计失败 action={} targetId={}: {}", action, targetId, e.getMessage());
        }
    }

    private static Long currentUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(RoleAuthInterceptor.ATTR_USER_ID);
        return userId == null ? 0L : userId;
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
