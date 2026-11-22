package com.sqlaudit.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlaudit.audit.AuditWriter;
import com.sqlaudit.common.enums.RollbackStatus;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.common.model.AuditEvent;
import com.sqlaudit.executor.gateway.ProductionGateway;
import com.sqlaudit.rollback.reverse.ReverseStatement;
import com.sqlaudit.storage.dao.RollbackDao;
import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.storage.model.RollbackSql;
import com.sqlaudit.storage.model.RollbackStatement;
import com.sqlaudit.storage.model.TicketUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 回滚执行服务（设计文档 §5.9 回放阶段 + §5.6 依赖检测）。
 *
 * <p>把已生成（generated/verified）的反向语句真正应用到生产库。执行协议：</p>
 * <ol>
 *   <li>校验回滚主记录存在、状态允许执行（generated/verified）、未过期；</li>
 *   <li>依赖检测（§5.6）：preimage_hash 非空时须重查当前行比对——前镜像 SELECT 与主键列
 *       未持久化，本版留 TODO 放行（见 {@link #dependencyCheck}）；</li>
 *   <li>分批流式读取反向语句，逐条经 {@link ProductionGateway#executeInTransaction} 执行；
 *       反向语句通常带主键 WHERE、幂等，不做 SELECT FOR UPDATE，直接执行 + 校验 affected&gt;0；</li>
 *   <li>每条成功后 {@code markStatementApplied}（逐条幂等）；全部成功 → applied；
 *       中途失败 → 0 条 applied 标 failed、部分 applied 标 partial（已应用的保留标记）；</li>
 *   <li>逐条写 {@code ROLLBACK_STATEMENT_APPLIED}、结束写 {@code ROLLBACK_EXECUTE} 审计事件。</li>
 * </ol>
 */
public class RollbackExecutor {

    private static final Logger log = LoggerFactory.getLogger(RollbackExecutor.class);

    /** 单批读取反向语句条数（流式，避免一次性加载全部进内存）。 */
    private static final int BATCH_SIZE = 200;

    private static final String STATUS_APPLIED = "applied";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_PARTIAL = "partial";

    private final RollbackDao rollbackDao;
    private final TicketUnitDao ticketUnitDao;
    private final ProductionGateway gateway;
    private final AuditWriter auditWriter;
    private final ZoneId zone;
    private final ObjectMapper mapper = new ObjectMapper();

    public RollbackExecutor(RollbackDao rollbackDao, TicketUnitDao ticketUnitDao,
                            ProductionGateway gateway, AuditWriter auditWriter, ZoneId zone) {
        this.rollbackDao = rollbackDao;
        this.ticketUnitDao = ticketUnitDao;
        this.gateway = gateway;
        this.auditWriter = auditWriter;
        this.zone = zone;
    }

    /**
     * 执行回滚。
     *
     * @param rollbackId 回滚主记录 id
     * @param operatorId 操作者用户 id（写审计）
     * @return 执行结果（终态 applied / failed / partial）
     */
    public Result executeRollback(long rollbackId, long operatorId) {
        LocalDateTime now = LocalDateTime.now();

        // 1. 存在性 + 状态 + 有效期校验
        RollbackSql rollback = rollbackDao.findById(rollbackId);
        if (rollback == null) {
            throw new SqlauditException("ROLLBACK_NOT_FOUND", "回滚记录不存在: id=" + rollbackId);
        }
        RollbackStatus status = parseStatus(rollback.getStatus());
        if (status == null) {
            throw new SqlauditException("ROLLBACK_INVALID_STATUS",
                    "未知回滚状态: " + rollback.getStatus());
        }
        if (status != RollbackStatus.GENERATED && status != RollbackStatus.VERIFIED) {
            throw new SqlauditException("ROLLBACK_NOT_EXECUTABLE",
                    "回滚当前状态 " + rollback.getStatus() + " 不允许执行（仅 generated/verified 可执行）");
        }
        if (rollback.getExpiresAt() != null && !rollback.getExpiresAt().isAfter(now)) {
            rollbackDao.updateStatus(rollbackId, RollbackStatus.EXPIRED.name().toLowerCase(),
                    "回滚已过期（expires_at=" + rollback.getExpiresAt() + "），拒绝执行");
            throw new SqlauditException("ROLLBACK_EXPIRED", "回滚已过期，拒绝执行");
        }

        // 2. 依赖检测（§5.6）：前镜像指纹非空时须比对当前行，本版留 TODO 放行
        dependencyCheck(rollbackId, rollback);

        // 3. 解析生产实例（反向语句路由目标）
        long instanceId = resolveInstanceId(rollback);

        // 4. 分批流式执行反向语句（按序；单 unit 内反向语句逐行独立，无顺序依赖）
        int applied = 0;
        int total = 0;
        int offset = 0;
        try {
            while (true) {
                List<RollbackStatement> batch = rollbackDao.listStatements(rollbackId, offset, BATCH_SIZE);
                if (batch.isEmpty()) {
                    break;
                }
                for (RollbackStatement stmt : batch) {
                    total++;
                    String sql = renderStatementSql(stmt);
                    ProductionGateway.ExecutionResult r = gateway.executeInTransaction(
                            new ProductionGateway.ExecutionRequest(sql, null, null, null, instanceId));
                    if (r.outcome() != ProductionGateway.Outcome.SUCCESS) {
                        throw new ExecutionFailure("反向语句 " + stmt.getId()
                                + " 执行失败: " + r.errorMessage());
                    }
                    if (r.affectedRows() <= 0) {
                        throw new ExecutionFailure("反向语句 " + stmt.getId()
                                + " 影响行数为 0（目标行不存在或已被后续工单改动）");
                    }
                    rollbackDao.markStatementApplied(stmt.getId(), now);
                    applied++;
                    auditStatementApplied(operatorId, rollback, stmt, sql);
                }
                offset += batch.size();
            }
            rollbackDao.updateStatus(rollbackId, STATUS_APPLIED, null);
            auditExecute(operatorId, rollback, "SUCCESS", applied, total, null);
            return new Result(rollbackId, STATUS_APPLIED, applied, total, null);
        } catch (ExecutionFailure e) {
            String finalStatus = applied == 0 ? STATUS_FAILED : STATUS_PARTIAL;
            rollbackDao.updateStatus(rollbackId, finalStatus, truncate(e.getMessage(), 500));
            auditExecute(operatorId, rollback, finalStatus.toUpperCase(), applied, total, e.getMessage());
            log.warn("[rollback-exec] rollbackId={} 执行中断 status={} applied={}/{}: {}",
                    rollbackId, finalStatus, applied, total, e.getMessage());
            return new Result(rollbackId, finalStatus, applied, total, e.getMessage());
        }
    }

    /**
     * 依赖检测（§5.6）：rollback_sql.preimage_hash 非空时，回滚执行前须按主键重查当前行、
     * 用同一口径重算前镜像 hash 比对——不一致说明数据已被后续工单改动，回滚会覆盖别人写入，须拒绝。
     *
     * <p>TODO(W2)：前镜像 SELECT 语句与主键列未持久化到 rollback_sql，本版无数据源可重查；
     * 装配后由 worker 侧 {@code PreimagePlanner} 按主键重查当前行并重算 hash，再回到本方法拒绝或放行。</p>
     */
    private void dependencyCheck(long rollbackId, RollbackSql rollback) {
        if (rollback.getPreimageHash() == null) {
            return; // 无前镜像（如 INSERT 反向为 DELETE），不适用依赖检测
        }
        log.warn("[rollback-exec] rollbackId={} 存在 preimage_hash，依赖检测尚未装配（TODO W2），当前放行",
                rollbackId);
    }

    /** 反向语句 → 生产实例 id（rollback_sql.unit_id 即 ticket_unit.id）。 */
    private long resolveInstanceId(RollbackSql rollback) {
        if (rollback.getUnitId() == null) {
            throw new SqlauditException("ROLLBACK_NO_UNIT", "回滚记录缺少关联执行单元: id=" + rollback.getId());
        }
        TicketUnit unit = ticketUnitDao.findById(rollback.getUnitId());
        if (unit == null || unit.getInstanceId() == null) {
            throw new SqlauditException("ROLLBACK_NO_INSTANCE",
                    "回滚记录关联的执行单元不存在或缺少实例: unitId=" + rollback.getUnitId());
        }
        return unit.getInstanceId();
    }

    /**
     * 参数化反向语句 → 可执行字面量 SQL。存储为 {@code reverse_sql}（? 占位）+ {@code value_params}
     * （JSON 数组），执行经 {@link ReverseStatement#displaySql()} 拼字面量（值来自前镜像回读，非用户输入；
     * 字符串单引号已转义）。ProductionGateway 接口仅接受整条 SQL 文本，不支持参数绑定。
     * 亦用于 {@code GET /api/rollbacks/{id}/statements} 展示明细。
     */
    public String renderStatementSql(RollbackStatement stmt) {
        String sql = new String(stmt.getReverseSql(), StandardCharsets.UTF_8);
        List<Object> params = parseParams(stmt.getValueParams());
        return new ReverseStatement(sql, params).displaySql();
    }

    private List<Object> parseParams(byte[] valueParams) {
        if (valueParams == null || valueParams.length == 0) {
            return List.of();
        }
        try {
            return mapper.readValue(valueParams, new TypeReference<List<Object>>() {
            });
        } catch (IOException e) {
            throw new SqlauditException("ROLLBACK_PARAM_PARSE_FAILED",
                    "反向语句参数解析失败", e);
        }
    }

    private void auditStatementApplied(long operatorId, RollbackSql rollback,
                                       RollbackStatement stmt, String sql) {
        try {
            auditWriter.append(AuditEvent.builder()
                    .actorId(operatorId)
                    .actorName(String.valueOf(operatorId))
                    .actorRole("DBA")
                    .action("ROLLBACK_STATEMENT_APPLIED")
                    .targetType("rollback_statement")
                    .targetId(String.valueOf(stmt.getId()))
                    .instanceId(null)
                    .detailPlaintext("{\"rollbackId\":" + rollback.getId()
                            + ",\"stmtOrder\":" + stmt.getStmtOrder()
                            + ",\"sql\":\"" + escape(sql) + "\"}")
                    .result("SUCCESS")
                    .traceId("rollback-" + rollback.getId())
                    .createdAt(OffsetDateTime.now(zone))
                    .build());
        } catch (Exception e) {
            log.warn("[rollback-exec] 语句审计失败 rollbackId={} statementId={}: {}",
                    rollback.getId(), stmt.getId(), e.getMessage());
        }
    }

    private void auditExecute(long operatorId, RollbackSql rollback, String result,
                              int applied, int total, String message) {
        try {
            auditWriter.append(AuditEvent.builder()
                    .actorId(operatorId)
                    .actorName(String.valueOf(operatorId))
                    .actorRole("DBA")
                    .action("ROLLBACK_EXECUTE")
                    .targetType("rollback")
                    .targetId(String.valueOf(rollback.getId()))
                    .instanceId(null)
                    .detailPlaintext("{\"rollbackId\":" + rollback.getId()
                            + ",\"unitId\":" + (rollback.getUnitId() == null ? "null" : rollback.getUnitId())
                            + ",\"applied\":" + applied
                            + ",\"total\":" + total
                            + ",\"message\":" + (message == null ? "null" : "\"" + escape(message) + "\"") + "}")
                    .result(result)
                    .traceId("rollback-" + rollback.getId())
                    .createdAt(OffsetDateTime.now(zone))
                    .build());
        } catch (Exception e) {
            log.warn("[rollback-exec] 执行审计失败 rollbackId={}: {}", rollback.getId(), e.getMessage());
        }
    }

    private static RollbackStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return RollbackStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 执行期失败（区别于校验期 SqlauditException，走状态流转 + 审计）。 */
    private static class ExecutionFailure extends RuntimeException {
        ExecutionFailure(String message) {
            super(message);
        }
    }

    /** 执行结果。 */
    public record Result(long rollbackId, String status, int applied, int total, String message) {
    }
}
