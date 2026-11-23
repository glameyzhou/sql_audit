package com.sqlaudit.server.web;

import com.sqlaudit.audit.AuditWriter;
import com.sqlaudit.common.enums.AuditAction;
import com.sqlaudit.common.enums.AuditResult;
import com.sqlaudit.common.enums.AuditTargetType;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.Environment;
import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.enums.InstanceStatus;
import com.sqlaudit.common.enums.PermissionCode;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.common.model.AuditEvent;
import com.sqlaudit.server.auth.RequirePermission;
import com.sqlaudit.server.auth.RoleAuthInterceptor;
import com.sqlaudit.server.config.FlowConfigLoader;
import com.sqlaudit.server.config.RuleConfigService;
import com.sqlaudit.server.service.InstanceCredentialService;
import com.sqlaudit.storage.dao.FlowRuleDao;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.dao.RuleDao;
import com.sqlaudit.storage.model.FlowRule;
import com.sqlaudit.storage.model.Instance;
import com.sqlaudit.storage.model.Rule;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
    private final RuleConfigService ruleConfigService;
    private final ObjectProvider<FlowConfigLoader> flowConfigLoader;
    private final InstanceCredentialService credentialService;
    private final JdbcTemplate jdbc;
    private final AuditWriter auditWriter;
    private final ZoneId zone;

    public ConfigController(InstanceDao instanceDao, RuleDao ruleDao, FlowRuleDao flowRuleDao,
                            RuleConfigService ruleConfigService, ObjectProvider<FlowConfigLoader> flowConfigLoader,
                            InstanceCredentialService credentialService, JdbcTemplate jdbc,
                            AuditWriter auditWriter, ZoneId zone) {
        this.instanceDao = instanceDao;
        this.ruleDao = ruleDao;
        this.flowRuleDao = flowRuleDao;
        this.ruleConfigService = ruleConfigService;
        this.flowConfigLoader = flowConfigLoader;
        this.credentialService = credentialService;
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
    @RequirePermission(PermissionCode.INSTANCE_ADMIN)
    public List<Instance> instances() {
        return instanceDao.listAll();
    }

    // ---------------- 实例 CRUD（ADMIN） ----------------

    /**
     * 实例凭据概要（编辑回填账号名）：只返回账号类型 + 账号名，绝不回显密码 / 密文。
     */
    @GetMapping("/instances/{id}/credentials")
    @RequirePermission(PermissionCode.INSTANCE_ADMIN)
    public List<Map<String, Object>> instanceCredentials(@PathVariable long id) {
        return credentialService.describe(id);
    }

    /**
     * 新增实例。请求体可携带 {@code username / password}——所有环境（dev/test/prod）一视同仁，
     * 随实例加密落库为 RO_META + RW_EXEC 两条凭据（见 {@link InstanceCredentialService}）。
     */
    @PostMapping("/instances")
    @RequirePermission(PermissionCode.INSTANCE_ADMIN)
    public Map<String, Object> createInstance(@RequestBody InstanceSaveRequest req, HttpServletRequest request) {
        validateCredential(req);
        Instance instance = req.toInstance();
        // 前端不再暴露 Owner ID：未显式指定时以当前登录用户为 Owner（自动进入该实例高危审批链）；
        // @RequestBody 显式携带 ownerId 时仍尊重显式值
        if (instance.getOwnerId() == null) {
            instance.setOwnerId(currentUserId(request));
        }
        instance.setCreatedBy(currentUserId(request));
        long id = instanceDao.insert(instance);
        boolean credentialsSaved = saveCredentials(id, req);
        audit(request, AuditAction.INSTANCE_CREATE, AuditTargetType.INSTANCE, id, id,
                "{\"name\":\"" + instance.getName() + "\",\"credentialsSaved\":" + credentialsSaved + "}");
        return Map.of("id", id);
    }

    @PutMapping("/instances/{id}")
    @RequirePermission(PermissionCode.INSTANCE_ADMIN)
    public ResponseEntity<Map<String, Object>> updateInstance(@PathVariable long id,
                                                              @RequestBody InstanceSaveRequest req,
                                                              HttpServletRequest request) {
        validateCredential(req);
        Instance instance = req.toInstance();
        instance.setId(id);
        // 编辑表单不提交 Owner / replica_dsn / credential_id：缺省沿用原值，
        // 避免把 NOT NULL 的 owner_id 刷成 null、或把未展示的字段误清空
        Instance existing = instanceDao.findById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (instance.getOwnerId() == null) {
            instance.setOwnerId(existing.getOwnerId());
        }
        if (instance.getReplicaDsn() == null) {
            instance.setReplicaDsn(existing.getReplicaDsn());
        }
        if (instance.getCredentialId() == null) {
            instance.setCredentialId(existing.getCredentialId());
        }
        if (instance.getStatus() == null) {
            instance.setStatus(InstanceStatus.ACTIVE);
        }
        int rows = instanceDao.update(instance);
        if (rows == 0) {
            return ResponseEntity.notFound().build();
        }
        boolean credentialsSaved = saveCredentials(id, req);
        audit(request, AuditAction.INSTANCE_UPDATE, AuditTargetType.INSTANCE, id, id,
                "{\"name\":\"" + instance.getName() + "\",\"credentialsSaved\":" + credentialsSaved + "}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    @DeleteMapping("/instances/{id}")
    @RequirePermission(PermissionCode.INSTANCE_ADMIN)
    public ResponseEntity<Map<String, Object>> deleteInstance(@PathVariable long id, HttpServletRequest request) {
        int rows = instanceDao.delete(id);
        if (rows == 0) {
            return ResponseEntity.notFound().build();
        }
        audit(request, AuditAction.INSTANCE_DELETE, AuditTargetType.INSTANCE, id, id, "{\"id\":" + id + "}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    // ---------------- 规则 CRUD（ADMIN/DBA） ----------------

    @PostMapping("/rules")
    @RequirePermission(PermissionCode.CONFIG_RULE_FLOW)
    public Map<String, Object> createRule(@RequestBody Rule rule, HttpServletRequest request) {
        long id = ruleDao.insert(rule);
        audit(request, AuditAction.RULE_CREATE, AuditTargetType.RULE, id, null, "{\"code\":\"" + rule.getCode() + "\"}");
        ruleConfigService.reload();
        return Map.of("id", id);
    }

    @PutMapping("/rules/{id}")
    @RequirePermission(PermissionCode.CONFIG_RULE_FLOW)
    public ResponseEntity<Map<String, Object>> updateRule(@PathVariable long id,
                                                          @RequestBody Rule rule,
                                                          HttpServletRequest request) {
        rule.setId(id);
        int rows = ruleDao.update(rule);
        if (rows == 0) {
            return ResponseEntity.notFound().build();
        }
        audit(request, AuditAction.RULE_UPDATE, AuditTargetType.RULE, id, null, "{\"code\":\"" + rule.getCode() + "\"}");
        ruleConfigService.reload();
        return ResponseEntity.ok(Map.of("id", id));
    }

    @DeleteMapping("/rules/{id}")
    @RequirePermission(PermissionCode.CONFIG_RULE_FLOW)
    public ResponseEntity<Map<String, Object>> deleteRule(@PathVariable long id, HttpServletRequest request) {
        int rows = ruleDao.delete(id);
        if (rows == 0) {
            return ResponseEntity.notFound().build();
        }
        audit(request, AuditAction.RULE_DELETE, AuditTargetType.RULE, id, null, "{\"id\":" + id + "}");
        ruleConfigService.reload();
        return ResponseEntity.ok(Map.of("id", id));
    }

    // ---------------- 审批流 CRUD（ADMIN/DBA） ----------------

    @PostMapping("/flows")
    @RequirePermission(PermissionCode.CONFIG_RULE_FLOW)
    public Map<String, Object> createFlow(@RequestBody FlowRule flowRule, HttpServletRequest request) {
        flowRule.setCreatedBy(currentUserId(request));
        long id = flowRuleDao.insert(flowRule);
        audit(request, AuditAction.FLOW_CREATE, AuditTargetType.FLOW_RULE, id, flowRule.getInstanceId(),
                "{\"name\":\"" + flowRule.getName() + "\"}");
        flowConfigLoader.ifAvailable(FlowConfigLoader::reload);
        return Map.of("id", id);
    }

    @PutMapping("/flows/{id}")
    @RequirePermission(PermissionCode.CONFIG_RULE_FLOW)
    public ResponseEntity<Map<String, Object>> updateFlow(@PathVariable long id,
                                                          @RequestBody FlowRule flowRule,
                                                          HttpServletRequest request) {
        flowRule.setId(id);
        int rows = flowRuleDao.update(flowRule);
        if (rows == 0) {
            return ResponseEntity.notFound().build();
        }
        audit(request, AuditAction.FLOW_UPDATE, AuditTargetType.FLOW_RULE, id, flowRule.getInstanceId(),
                "{\"name\":\"" + flowRule.getName() + "\"}");
        flowConfigLoader.ifAvailable(FlowConfigLoader::reload);
        return ResponseEntity.ok(Map.of("id", id));
    }

    @DeleteMapping("/flows/{id}")
    @RequirePermission(PermissionCode.CONFIG_RULE_FLOW)
    public ResponseEntity<Map<String, Object>> deleteFlow(@PathVariable long id, HttpServletRequest request) {
        int rows = flowRuleDao.delete(id);
        if (rows == 0) {
            return ResponseEntity.notFound().build();
        }
        audit(request, AuditAction.FLOW_DELETE, AuditTargetType.FLOW_RULE, id, null, "{\"id\":" + id + "}");
        flowConfigLoader.ifAvailable(FlowConfigLoader::reload);
        return ResponseEntity.ok(Map.of("id", id));
    }

    // ---------------- 实例凭据（随实例保存，所有环境一致） ----------------

    /**
     * 凭据写入前的合法性校验：给了密码就必须给账号（缺账号直接 400，不落半截数据）。
     * 只给账号不给密码 = 不修改凭据（编辑场景密码框留空）。
     */
    private static void validateCredential(InstanceSaveRequest req) {
        if (hasPassword(req) && (req.username() == null || req.username().isBlank())) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "凭据账号不能为空");
        }
    }

    /** 凭据是否随本次请求保存；返回 true 表示已写 RO_META + RW_EXEC。 */
    private boolean saveCredentials(long instanceId, InstanceSaveRequest req) {
        if (!hasPassword(req)) {
            return false;
        }
        credentialService.savePair(instanceId, req.username(), req.password());
        return true;
    }

    private static boolean hasPassword(InstanceSaveRequest req) {
        return req.password() != null && !req.password().isEmpty();
    }

    /**
     * 实例新增 / 编辑请求体：{@link Instance} 的全部业务字段 + 一组数据库凭据
     * （{@code username / password}，仅用于落库，不随实例列表回显）。
     */
    public record InstanceSaveRequest(Long id, String name, Dialect dialect, Environment environment,
                                      Long ownerId, String host, Integer port, String defaultSchema,
                                      String replicaDsn, Long credentialId, InstanceStatus status,
                                      String username, String password) {

        Instance toInstance() {
            Instance instance = new Instance();
            instance.setId(id);
            instance.setName(name);
            instance.setDialect(dialect);
            instance.setEnvironment(environment);
            instance.setOwnerId(ownerId);
            instance.setHost(host);
            instance.setPort(port);
            instance.setDefaultSchema(defaultSchema);
            instance.setReplicaDsn(replicaDsn);
            instance.setCredentialId(credentialId);
            instance.setStatus(status);
            return instance;
        }
    }

    // ---------------- 审计与工具 ----------------

    private void audit(HttpServletRequest request, AuditAction action, AuditTargetType targetType,
                       long targetId, Long instanceId, String detail) {
        try {
            Long actorId = (Long) request.getAttribute(RoleAuthInterceptor.ATTR_USER_ID);
            String actorName = (String) request.getAttribute(RoleAuthInterceptor.ATTR_USERNAME);
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) request.getAttribute(RoleAuthInterceptor.ATTR_ROLES);
            String actorRole = roles != null && !roles.isEmpty() ? roles.get(0) : null;
            AuditEvent event = AuditEvent.builder()
                    .actorId(actorId == null ? 0L : actorId)
                    .actorName(actorName == null ? AuditEvent.SYSTEM_ACTOR_NAME : actorName)
                    .actorRole(actorRole == null ? AuditEvent.SYSTEM_ACTOR_ROLE : actorRole)
                    .action(action)
                    .targetType(targetType)
                    .targetId(String.valueOf(targetId))
                    .instanceId(instanceId)
                    .detailPlaintext(detail)
                    .result(AuditResult.SUCCESS)
                    // audit_log.trace_id NOT NULL：配置操作链路标识，与 ticket-/rollback- 前缀口径一致
                    .traceId("config-" + targetType.getCode() + "-" + targetId)
                    .createdAt(OffsetDateTime.now(zone))
                    .build();
            auditWriter.append(event);
        } catch (Exception e) {
            log.warn("配置写操作审计失败 action={} targetId={}: {}", action.getCode(), targetId, e.getMessage());
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
