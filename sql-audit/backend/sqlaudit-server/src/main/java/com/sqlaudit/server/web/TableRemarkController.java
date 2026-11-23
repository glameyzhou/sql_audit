package com.sqlaudit.server.web;

import com.sqlaudit.audit.AuditWriter;
import com.sqlaudit.common.enums.AuditAction;
import com.sqlaudit.common.enums.AuditResult;
import com.sqlaudit.common.enums.AuditTargetType;
import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.common.model.AuditEvent;
import com.sqlaudit.server.auth.AuditActor;
import com.sqlaudit.server.auth.AuditEvents;
import com.sqlaudit.server.auth.CurrentUser;
import com.sqlaudit.server.auth.RoleAuthInterceptor;
import com.sqlaudit.server.service.DataScopeService;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.dao.TableRemarkDao;
import com.sqlaudit.storage.model.Instance;
import com.sqlaudit.storage.model.TableRemark;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表备注词典端点（契约 §6：{@code GET /api/table-remarks} → {@code query.run}；
 * {@code PUT /api/table-remarks} → {@code instance.manage}，权限由拦截器按接口绑定判定，
 * 代码内不做角色判断）。
 *
 * <p>备注是平台侧词典（t_table_remark），展示时优先于原库原生注释（合并逻辑在前端，
 * 后端只下发词典行）。「清空备注 = 删除行」：PUT 的 remark 为空 / 空白即删除，
 * 正常写为 upsert。GET 校验实例数据范围（越权 403），PUT 属实例管理动作不做数据范围
 * 限制（与 {@code /api/config/instances/**} 同口径）。</p>
 */
@RestController
@RequestMapping("/api/table-remarks")
public class TableRemarkController {

    private static final Logger log = LoggerFactory.getLogger(TableRemarkController.class);

    /** 备注长度上限（与 t_table_remark.remark VARCHAR(255) 一致）。 */
    private static final int MAX_REMARK_LENGTH = 255;

    private final TableRemarkDao tableRemarkDao;
    private final InstanceDao instanceDao;
    private final DataScopeService dataScopeService;
    private final AuditWriter auditWriter;
    private final ZoneId zone;

    public TableRemarkController(TableRemarkDao tableRemarkDao, InstanceDao instanceDao,
                                 DataScopeService dataScopeService, AuditWriter auditWriter,
                                 ZoneId zone) {
        this.tableRemarkDao = tableRemarkDao;
        this.instanceDao = instanceDao;
        this.dataScopeService = dataScopeService;
        this.auditWriter = auditWriter;
        this.zone = zone;
    }

    /** 某实例某库（PG 语义为 schema）下的平台备注词典：{@code [{ tableName, remark }]}。 */
    @GetMapping
    public List<Map<String, String>> list(@RequestParam long instanceId,
                                          @RequestParam String schemaName,
                                          HttpServletRequest request) {
        if (schemaName == null || schemaName.isBlank()) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "库名不能为空");
        }
        dataScopeService.requireInScope(dataScopeService.requestScope(request), instanceId);
        List<Map<String, String>> out = new ArrayList<>();
        for (TableRemark r : tableRemarkDao.findByInstanceAndSchema(instanceId, schemaName)) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("tableName", r.getTableName());
            m.put("remark", r.getRemark());
            out.add(m);
        }
        return out;
    }

    /** 写入 / 覆盖一条备注；remark 为空 / 空白即删除该行。返回 {@code { tableName, remark }}。 */
    @PutMapping
    public ResponseEntity<Map<String, String>> upsert(@RequestBody UpsertRequest req,
                                                      HttpServletRequest request) {
        if (req.schemaName() == null || req.schemaName().isBlank()) {
            return badRequest("库名不能为空");
        }
        if (req.tableName() == null || req.tableName().isBlank()) {
            return badRequest("表名不能为空");
        }
        String remark = req.remark() == null ? "" : req.remark().trim();
        if (remark.length() > MAX_REMARK_LENGTH) {
            return badRequest("备注最长 " + MAX_REMARK_LENGTH + " 字符");
        }
        Instance instance = instanceDao.findById(req.instanceId());
        if (instance == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "code", ErrorCode.INSTANCE_NOT_FOUND.getCode(),
                    "message", "实例不存在: id=" + req.instanceId()));
        }
        CurrentUser operator = RoleAuthInterceptor.currentUser(request);
        if (remark.isEmpty()) {
            tableRemarkDao.delete(req.instanceId(), req.schemaName(), req.tableName());
        } else {
            TableRemark model = new TableRemark();
            model.setInstanceId(req.instanceId());
            model.setSchemaName(req.schemaName());
            model.setTableName(req.tableName());
            model.setRemark(remark);
            model.setCreatedBy(operator.id());
            tableRemarkDao.upsert(model);
        }
        audit(request, req, remark);
        return ResponseEntity.ok(Map.of("tableName", req.tableName(), "remark", remark));
    }

    // ---------------- 内部工具 ----------------

    private void audit(HttpServletRequest request, UpsertRequest req, String remark) {
        try {
            AuditActor actor = AuditActor.of(RoleAuthInterceptor.currentUser(request), request);
            String detail = "{\"instanceId\":" + req.instanceId()
                    + ",\"schemaName\":\"" + req.schemaName() + "\""
                    + ",\"tableName\":\"" + req.tableName() + "\""
                    + (remark.isEmpty() ? ",\"action\":\"clear\"" : ",\"action\":\"set\",\"remark\":\"" + remark + "\"")
                    + "}";
            auditWriter.append(AuditEvents.base(actor)
                    .action(AuditAction.TABLE_REMARK_UPDATE)
                    .targetType(AuditTargetType.TABLE_REMARK)
                    .targetId(req.instanceId() + "/" + req.schemaName() + "/" + req.tableName())
                    .detailPlaintext(detail)
                    .result(AuditResult.SUCCESS)
                    .traceId("table-remark-" + req.instanceId())
                    .createdAt(OffsetDateTime.now(zone))
                    .build());
        } catch (Exception e) {
            log.warn("表备注审计失败 instanceId={} schema={} table={}: {}",
                    req.instanceId(), req.schemaName(), req.tableName(), e.getMessage());
        }
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", ErrorCode.INVALID_REQUEST.getCode(), "message", message));
    }

    // ---------------- 请求体 ----------------

    /** 备注写入请求体（remark 为空 / 空白 = 删除该行）。 */
    public record UpsertRequest(long instanceId, String schemaName, String tableName, String remark) {
    }
}
