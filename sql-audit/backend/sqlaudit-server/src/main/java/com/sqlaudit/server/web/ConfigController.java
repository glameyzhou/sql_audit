package com.sqlaudit.server.web;

import com.sqlaudit.audit.AuditWriter;
import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApproverType;
import com.sqlaudit.common.enums.AuditAction;
import com.sqlaudit.common.enums.AuditResult;
import com.sqlaudit.common.enums.AuditTargetType;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.enums.InstanceStatus;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.UserStatus;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.common.model.AuditEvent;
import com.sqlaudit.server.auth.AuditActor;
import com.sqlaudit.server.auth.AuditEvents;
import com.sqlaudit.server.auth.CurrentUser;
import com.sqlaudit.server.auth.RoleAuthInterceptor;
import com.sqlaudit.server.config.FlowConfigLoader;
import com.sqlaudit.server.config.FlowNodeService;
import com.sqlaudit.server.config.RuleConfigService;
import com.sqlaudit.server.service.InstanceCredentialService;
import com.sqlaudit.storage.dao.FlowNodeDao;
import com.sqlaudit.storage.dao.FlowRuleDao;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.dao.RuleDao;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.model.FlowNode;
import com.sqlaudit.storage.model.FlowRule;
import com.sqlaudit.storage.model.Instance;
import com.sqlaudit.storage.model.Role;
import com.sqlaudit.storage.model.Rule;
import com.sqlaudit.storage.model.User;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 系统配置端点（契约 §6 审批流配置 / 实例段）。
 *
 * <p>实例与凭据管理（{@code instance.manage}）、审核规则（{@code rule.manage}）、审批流
 * （{@code flow.manage}）各自由权限点控制（拦截器判定，代码里无权限码字面量）。
 * <strong>无环境维度</strong>：实例 / 工单 / 审批流均无 environment 字段；实例负责人
 * （{@code ownerId}）必填；未删除实例中 name 唯一（服务层校验）。审批流按「范围 × 风险
 * 等级」至多一条（重复 → 409 {@code DUPLICATE_FLOW}）；节点整体替换，保存期校验见
 * {@link FlowNodeService}。流程 / 节点变更后 {@code reload()} 审批链即时生效。
 * 写操作逐条写审计（操作人取登录态 + clientIp / userAgent）。</p>
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    private final InstanceDao instanceDao;
    private final RuleDao ruleDao;
    private final FlowRuleDao flowRuleDao;
    private final FlowNodeDao flowNodeDao;
    private final FlowNodeService flowNodeService;
    private final RuleConfigService ruleConfigService;
    private final FlowConfigLoader flowConfigLoader;
    private final InstanceCredentialService credentialService;
    private final UserDao userDao;
    private final RoleDao roleDao;
    private final AuditWriter auditWriter;
    private final ZoneId zone;

    public ConfigController(InstanceDao instanceDao, RuleDao ruleDao, FlowRuleDao flowRuleDao,
                            FlowNodeDao flowNodeDao, FlowNodeService flowNodeService,
                            RuleConfigService ruleConfigService, FlowConfigLoader flowConfigLoader,
                            InstanceCredentialService credentialService, UserDao userDao,
                            RoleDao roleDao, AuditWriter auditWriter, ZoneId zone) {
        this.instanceDao = instanceDao;
        this.ruleDao = ruleDao;
        this.flowRuleDao = flowRuleDao;
        this.flowNodeDao = flowNodeDao;
        this.flowNodeService = flowNodeService;
        this.ruleConfigService = ruleConfigService;
        this.flowConfigLoader = flowConfigLoader;
        this.credentialService = credentialService;
        this.userDao = userDao;
        this.roleDao = roleDao;
        this.auditWriter = auditWriter;
        this.zone = zone;
    }

    // ---------------- 审核规则（rule.manage） ----------------

    @GetMapping("/rules")
    public List<Rule> rules() {
        return ruleDao.listAll();
    }

    @PostMapping("/rules")
    public Map<String, Object> createRule(@RequestBody Rule rule, HttpServletRequest request) {
        long id = ruleDao.insert(rule);
        audit(request, AuditAction.RULE_CREATE, AuditTargetType.RULE, id, null, "{\"code\":\"" + rule.getCode() + "\"}");
        ruleConfigService.reload();
        return Map.of("id", id);
    }

    @PutMapping("/rules/{id}")
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
    public ResponseEntity<Map<String, Object>> deleteRule(@PathVariable long id, HttpServletRequest request) {
        int rows = ruleDao.delete(id);
        if (rows == 0) {
            return ResponseEntity.notFound().build();
        }
        audit(request, AuditAction.RULE_DELETE, AuditTargetType.RULE, id, null, "{\"id\":" + id + "}");
        ruleConfigService.reload();
        return ResponseEntity.ok(Map.of("id", id));
    }

    // ---------------- 审批流（flow.manage） ----------------

    /** 审批流规则列表（范围：全局 / 实例名；风险等级；启用）。 */
    @GetMapping("/flows")
    public List<FlowView> flows() {
        List<FlowView> out = new ArrayList<>();
        for (FlowRule rule : flowRuleDao.listAll()) {
            out.add(viewOf(rule));
        }
        return out;
    }

    /** 新建审批流：同一范围（全局 / 某实例）+ 风险等级重复 → 409 DUPLICATE_FLOW。 */
    @PostMapping("/flows")
    public ResponseEntity<Map<String, Object>> createFlow(@RequestBody FlowCreateRequest req,
                                                          HttpServletRequest request) {
        if (req.riskLevel() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", ErrorCode.INVALID_REQUEST.getCode(), "message", "风险等级不能为空"));
        }
        validateInstanceScope(req.instanceId());
        if (flowRuleDao.findByScope(req.instanceId(), req.riskLevel()) != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", ErrorCode.DUPLICATE_FLOW.getCode(),
                    "message", "该范围下 " + req.riskLevel().getCode() + " 风险等级的审批流已存在"));
        }
        CurrentUser user = RoleAuthInterceptor.currentUser(request);
        FlowRule flowRule = new FlowRule();
        flowRule.setName(req.name() == null || req.name().isBlank() ? "未命名流程" : req.name().trim());
        flowRule.setInstanceId(req.instanceId());
        flowRule.setRiskLevel(req.riskLevel());
        flowRule.setEnabled(req.enabled() == null || req.enabled());
        flowRule.setCreatedBy(user.id());
        long id = flowRuleDao.insert(flowRule);
        audit(request, AuditAction.FLOW_CREATE, AuditTargetType.FLOW_RULE, id, req.instanceId(),
                "{\"name\":\"" + flowRule.getName() + "\"}");
        flowConfigLoader.reload();
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 更新审批流（范围与风险等级创建后不可改；只改 name / enabled）。 */
    @PutMapping("/flows/{id}")
    public ResponseEntity<Map<String, Object>> updateFlow(@PathVariable long id,
                                                          @RequestBody FlowUpdateRequest req,
                                                          HttpServletRequest request) {
        FlowRule existing = flowRuleDao.findById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        FlowRule flowRule = new FlowRule();
        flowRule.setId(id);
        flowRule.setName(req.name());
        flowRule.setEnabled(req.enabled());
        int rows = flowRuleDao.update(flowRule);
        if (rows == 0) {
            return ResponseEntity.notFound().build();
        }
        audit(request, AuditAction.FLOW_UPDATE, AuditTargetType.FLOW_RULE, id, existing.getInstanceId(),
                "{\"name\":\"" + req.name() + "\"}");
        flowConfigLoader.reload();
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 删除审批流（连同节点）。 */
    @DeleteMapping("/flows/{id}")
    public ResponseEntity<Map<String, Object>> deleteFlow(@PathVariable long id, HttpServletRequest request) {
        FlowRule existing = flowRuleDao.findById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        flowRuleDao.delete(id);
        audit(request, AuditAction.FLOW_DELETE, AuditTargetType.FLOW_RULE, id, existing.getInstanceId(),
                "{\"id\":" + id + "}");
        flowConfigLoader.reload();
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 某条审批流规则的节点链（含 approverDisplay）。 */
    @GetMapping("/flows/{id}/nodes")
    public List<NodeView> flowNodes(@PathVariable long id) {
        List<NodeView> out = new ArrayList<>();
        for (FlowNode node : flowNodeDao.listByFlowRule(id)) {
            out.add(new NodeView(node.getId(), node.getNodeOrder(), node.getApproverType(),
                    node.getApproverRef(), approverDisplay(node), node.getMode()));
        }
        return out;
    }

    /** 整体替换某流程的节点（校验见 §5 / FlowNodeService；空数组 = 免审）。 */
    @PutMapping("/flows/{id}/nodes")
    public ResponseEntity<Map<String, Object>> replaceFlowNodes(
            @PathVariable long id, @RequestBody List<FlowNodeService.Request> requests,
            HttpServletRequest request) {
        if (flowRuleDao.findById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        flowNodeService.replaceNodes(id, requests);
        audit(request, AuditAction.FLOW_UPDATE, AuditTargetType.FLOW_RULE, id, null,
                "{\"nodeCount\":" + (requests == null ? 0 : requests.size()) + "}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    // ---------------- 实例（instance.manage；不受数据范围限制） ----------------

    /** 实例列表（管理页；含负责人名）。 */
    @GetMapping("/instances")
    public List<InstanceView> instances() {
        List<InstanceView> out = new ArrayList<>();
        for (Instance instance : instanceDao.listAll()) {
            out.add(viewOf(instance));
        }
        return out;
    }

    /**
     * 实例凭据概要（编辑回填账号名）：只返回账号类型 + 账号名，绝不回显密码 / 密文。
     */
    @GetMapping("/instances/{id}/credentials")
    public List<Map<String, Object>> instanceCredentials(@PathVariable long id) {
        return credentialService.describe(id);
    }

    /**
     * 新增实例（无环境维度；负责人必填；未删除实例中 name 唯一）。请求体可携带
     * {@code username / password}，随实例加密落库为 RO_META + RW_EXEC 两条凭据。
     */
    @PostMapping("/instances")
    public ResponseEntity<Map<String, Object>> createInstance(@RequestBody InstanceSaveRequest req,
                                                              HttpServletRequest request) {
        validateCredential(req);
        Instance instance = toInstance(req);
        validateOwner(instance.getOwnerId());
        validateNameUnique(instance.getName(), null);
        CurrentUser user = RoleAuthInterceptor.currentUser(request);
        instance.setCreatedBy(user.id());
        long id = instanceDao.insert(instance);
        boolean credentialsSaved = saveCredentials(id, req);
        audit(request, AuditAction.INSTANCE_CREATE, AuditTargetType.INSTANCE, id, id,
                "{\"name\":\"" + instance.getName() + "\",\"credentialsSaved\":" + credentialsSaved + "}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PutMapping("/instances/{id}")
    public ResponseEntity<Map<String, Object>> updateInstance(@PathVariable long id,
                                                              @RequestBody InstanceSaveRequest req,
                                                              HttpServletRequest request) {
        validateCredential(req);
        Instance existing = instanceDao.findById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        Instance instance = toInstance(req);
        instance.setId(id);
        validateOwner(instance.getOwnerId());
        validateNameUnique(instance.getName(), id);
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
    public ResponseEntity<Map<String, Object>> deleteInstance(@PathVariable long id, HttpServletRequest request) {
        int rows = instanceDao.delete(id);
        if (rows == 0) {
            return ResponseEntity.notFound().build();
        }
        audit(request, AuditAction.INSTANCE_DELETE, AuditTargetType.INSTANCE, id, id, "{\"id\":" + id + "}");
        return ResponseEntity.ok(Map.of("id", id));
    }

    // ---------------- 校验与工具 ----------------

    /** 凭据写入前的合法性校验：给了密码就必须给账号（缺账号直接 400，不落半截数据）。 */
    private static void validateCredential(InstanceSaveRequest req) {
        if (hasPassword(req) && (req.username() == null || req.username().isBlank())) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "凭据账号不能为空");
        }
    }

    /** 实例负责人必填且必须是 ACTIVE 用户（契约 §1.2：owner 必须是有效用户）。 */
    private void validateOwner(Long ownerId) {
        if (ownerId == null || ownerId <= 0) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "实例负责人（ownerId）必填");
        }
        User owner = userDao.findById(ownerId);
        if (owner == null || owner.getStatus() != UserStatus.ACTIVE) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "实例负责人不存在或已停用");
        }
    }

    /** 未删除实例中 name 唯一（软删同名可复用）。 */
    private void validateNameUnique(String name, Long excludeId) {
        if (name == null || name.isBlank()) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "实例名不能为空");
        }
        Instance existing = instanceDao.findByName(name.trim());
        if (existing != null && (excludeId == null || !existing.getId().equals(excludeId))) {
            throw new SqlauditException(ErrorCode.BUSINESS_ERROR, "实例名已存在: " + name.trim());
        }
    }

    /** 审批流范围引用实例时，实例必须存在（未软删）。 */
    private void validateInstanceScope(Long instanceId) {
        if (instanceId != null && instanceDao.findById(instanceId) == null) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "实例不存在: " + instanceId);
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

    // ---------------- 视图与 DTO ----------------

    /** 审批流视图（契约 §6）。 */
    public record FlowView(Long id, String name, Long instanceId, String instanceName,
                           RiskLevel riskLevel, boolean enabled, LocalDateTime createdAt,
                           LocalDateTime updatedAt) {
    }

    private FlowView viewOf(FlowRule rule) {
        String instanceName = null;
        if (rule.getInstanceId() != null) {
            Instance instance = instanceDao.findById(rule.getInstanceId());
            instanceName = instance == null ? null : instance.getName();
        }
        return new FlowView(rule.getId(), rule.getName(), rule.getInstanceId(), instanceName,
                rule.getRiskLevel(), rule.getEnabled() == null || rule.getEnabled(),
                rule.getCreatedAt(), rule.getUpdatedAt());
    }

    /** 节点视图（契约 §6：含 approverDisplay）。 */
    public record NodeView(Long id, Integer nodeOrder, ApproverType approverType,
                           String approverRef, String approverDisplay, ApprovalMode mode) {
    }

    private String approverDisplay(FlowNode node) {
        if (node.getApproverType() == null || node.getApproverRef() == null) {
            return null;
        }
        return switch (node.getApproverType()) {
            case USER -> {
                try {
                    User u = userDao.findById(Long.parseLong(node.getApproverRef()));
                    yield u == null ? null : u.getDisplayName();
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            case INSTANCE_OWNER -> "实例负责人";
            case ROLE -> {
                Role role = roleDao.findByCode(node.getApproverRef());
                yield role == null ? null : role.getName();
            }
        };
    }

    /** 实例视图（契约 §6：含 ownerName）。 */
    public record InstanceView(Long id, String name, Dialect dialect, Long ownerId, String ownerName,
                               String host, Integer port, String defaultSchema,
                               InstanceStatus status, LocalDateTime createdAt) {
    }

    private InstanceView viewOf(Instance instance) {
        String ownerName = null;
        if (instance.getOwnerId() != null) {
            User owner = userDao.findById(instance.getOwnerId());
            ownerName = owner == null ? null : owner.getDisplayName();
        }
        return new InstanceView(instance.getId(), instance.getName(), instance.getDialect(),
                instance.getOwnerId(), ownerName, instance.getHost(), instance.getPort(),
                instance.getDefaultSchema(), instance.getStatus(), instance.getCreatedAt());
    }

    /**
     * 实例新增 / 编辑请求体（契约 §6：无 environment / replicaDsn / credentialId；
     * ownerId 必填；一组数据库凭据仅用于落库，不随实例列表回显）。
     */
    public record InstanceSaveRequest(Long id, String name, Dialect dialect, Long ownerId,
                                      String host, Integer port, String defaultSchema,
                                      InstanceStatus status, String username, String password) {
    }

    private static Instance toInstance(InstanceSaveRequest req) {
        Instance instance = new Instance();
        instance.setId(req.id());
        instance.setName(req.name() == null ? null : req.name().trim());
        instance.setDialect(req.dialect());
        instance.setOwnerId(req.ownerId());
        instance.setHost(req.host());
        instance.setPort(req.port());
        instance.setDefaultSchema(req.defaultSchema());
        instance.setStatus(req.status() == null ? InstanceStatus.ACTIVE : req.status());
        return instance;
    }

    /** 审批流创建请求体。 */
    public record FlowCreateRequest(String name, Long instanceId, RiskLevel riskLevel, Boolean enabled) {
    }

    /** 审批流更新请求体（范围与风险等级创建后不可改）。 */
    public record FlowUpdateRequest(String name, Boolean enabled) {
    }

    // ---------------- 审计 ----------------

    private void audit(HttpServletRequest request, AuditAction action, AuditTargetType targetType,
                       long targetId, Long instanceId, String detail) {
        try {
            AuditActor actor = AuditActor.of(RoleAuthInterceptor.currentUser(request), request);
            auditWriter.append(AuditEvents.base(actor)
                    .action(action)
                    .targetType(targetType)
                    .targetId(String.valueOf(targetId))
                    .instanceId(instanceId)
                    .detailPlaintext(detail)
                    .result(AuditResult.SUCCESS)
                    .traceId("config-" + targetType.getCode() + "-" + targetId)
                    .createdAt(OffsetDateTime.now(zone))
                    .build());
        } catch (Exception e) {
            log.warn("配置写操作审计失败 action={} targetId={}: {}", action.getCode(), targetId, e.getMessage());
        }
    }
}
