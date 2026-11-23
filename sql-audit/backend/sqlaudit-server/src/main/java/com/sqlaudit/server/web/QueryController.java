package com.sqlaudit.server.web;

import com.sqlaudit.audit.AuditWriter;
import com.sqlaudit.common.enums.AuditAction;
import com.sqlaudit.common.enums.AuditResult;
import com.sqlaudit.common.enums.AuditTargetType;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.common.model.AuditEvent;
import com.sqlaudit.query.channel.QueryChannel;
import com.sqlaudit.query.channel.QueryResult;
import com.sqlaudit.server.auth.AuditActor;
import com.sqlaudit.server.auth.AuditEvents;
import com.sqlaudit.server.auth.CurrentUser;
import com.sqlaudit.server.auth.RoleAuthInterceptor;
import com.sqlaudit.server.service.DataScopeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 查询通道端点（README §4.1：三层保护在 QueryChannel 内；契约 §6 查询段）。
 *
 * <p>请求体去掉 {@code userId}——操作人一律取登录态（{@link CurrentUser}），
 * 审计写真实身份（含 clientIp / userAgent）。实例必须在该请求的数据范围内
 * （{@code INSTANCE_OUT_OF_SCOPE} 403）；单条查询 SQL ≤ 64 KiB（{@code SQL_TOO_LARGE} 400）。</p>
 */
@RestController
@RequestMapping("/api/query")
public class QueryController {

    /** 单条查询 SQL 上限：64 KiB（UTF-8 字节，契约 §4.6）。 */
    public static final int MAX_QUERY_SQL_BYTES = 64 * 1024;

    private final QueryChannel queryChannel;
    private final AuditWriter auditWriter;
    private final DataScopeService dataScopeService;
    private final ZoneId zone;

    public QueryController(QueryChannel queryChannel, AuditWriter auditWriter,
                           DataScopeService dataScopeService, ZoneId zone) {
        this.queryChannel = queryChannel;
        this.auditWriter = auditWriter;
        this.dataScopeService = dataScopeService;
        this.zone = zone;
    }

    @PostMapping
    public QueryResult execute(@RequestBody QueryRequest req, HttpServletRequest request) {
        CurrentUser user = RoleAuthInterceptor.currentUser(request);
        DataScopeService.Scope scope = dataScopeService.requestScope(request);
        dataScopeService.requireInScope(scope, req.instanceId());
        if (req.sql() != null && req.sql().getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_SQL_BYTES) {
            throw new SqlauditException(ErrorCode.SQL_TOO_LARGE,
                    "查询 SQL 过长（上限 " + MAX_QUERY_SQL_BYTES + " 字节）");
        }
        QueryResult result = queryChannel.execute(req.instanceId(), req.sql(), req.dialect());
        AuditActor actor = AuditActor.of(user, request);
        auditWriter.append(AuditEvents.base(actor)
                .action(AuditAction.QUERY_EXECUTED)
                .targetType(AuditTargetType.INSTANCE)
                .targetId(String.valueOf(req.instanceId()))
                .instanceId(req.instanceId())
                .detailPlaintext("{\"sql\":\"" + req.sql().replace("\\", "\\\\").replace("\"", "\\\"")
                        + "\",\"rowCount\":" + result.getRowCount() + "}")
                .result(result.isSuccess() ? AuditResult.SUCCESS : AuditResult.REJECTED)
                .traceId("query-inst" + req.instanceId())
                .createdAt(OffsetDateTime.now(zone))
                .build());
        return result;
    }

    /** dialect 按存储码（MYSQL / PG / IOTDB）反序列化，未知取值由统一错误出口转 400。 */
    public record QueryRequest(long instanceId, String sql, Dialect dialect) {
    }
}
