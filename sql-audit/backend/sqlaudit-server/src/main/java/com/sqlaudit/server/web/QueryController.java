package com.sqlaudit.server.web;

import com.sqlaudit.audit.AuditWriter;
import com.sqlaudit.common.enums.AuditAction;
import com.sqlaudit.common.enums.AuditResult;
import com.sqlaudit.common.enums.AuditTargetType;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.RoleCode;
import com.sqlaudit.common.model.AuditEvent;
import com.sqlaudit.query.channel.QueryChannel;
import com.sqlaudit.query.channel.QueryResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * 查询通道端点（§6.2：三层保护在 QueryChannel 内）。
 *
 * <p>每次查询全量进审计链（§6.4 query_executed 事件）；query_log 分表落库为 W2。</p>
 */
@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final QueryChannel queryChannel;
    private final AuditWriter auditWriter;

    public QueryController(QueryChannel queryChannel, AuditWriter auditWriter) {
        this.queryChannel = queryChannel;
        this.auditWriter = auditWriter;
    }

    @PostMapping
    public QueryResult execute(@RequestBody QueryRequest req) {
        QueryResult result = queryChannel.execute(req.instanceId(), req.sql(), req.dialect());
        auditWriter.append(AuditEvent.builder()
                .actorId(req.userId())
                .actorName(String.valueOf(req.userId()))
                .actorRole(RoleCode.DEV.getCode())
                .action(AuditAction.QUERY_EXECUTED)
                .targetType(AuditTargetType.INSTANCE)
                .targetId(String.valueOf(req.instanceId()))
                .instanceId(req.instanceId())
                .detailPlaintext("{\"sql\":\"" + req.sql().replace("\\", "\\\\").replace("\"", "\\\"")
                        + "\",\"rowCount\":" + result.getRowCount() + "}")
                .result(result.isSuccess() ? AuditResult.SUCCESS : AuditResult.REJECTED)
                .traceId("query-inst" + req.instanceId())
                .createdAt(OffsetDateTime.now())
                .build());
        return result;
    }

    /** dialect 按存储码（MYSQL / PG / IOTDB）反序列化，未知取值由统一错误出口转 400。 */
    public record QueryRequest(long instanceId, String sql, Dialect dialect, long userId) {
    }
}
