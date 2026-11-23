package com.sqlaudit.worker.gateway;

import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.GateCode;
import com.sqlaudit.common.enums.RollbackCoverage;
import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.common.model.RollbackPlan;
import com.sqlaudit.common.model.RuleHit;
import com.sqlaudit.executor.gate.ExecutionGate;
import com.sqlaudit.executor.gate.GateContext;
import com.sqlaudit.parser.SqlParserService;
import com.sqlaudit.rollback.capability.CapabilityContext;
import com.sqlaudit.rollback.capability.RollbackCapabilityAssessor;
import com.sqlaudit.worker.gateway.JdbcTableMetadataProvider.InstanceContext;
import com.sqlaudit.rule.TableMetadataProvider;
import com.sqlaudit.rule.engine.RuleEngine;
import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.model.Approval;
import com.sqlaudit.storage.model.Instance;
import com.sqlaudit.storage.model.TicketUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 执行前门禁上下文（§3.5 的 worker 侧装配）：为 {@link ExecutionGate} 提供门禁所需的
 * 外部数据——审批通过时间（审批时效门禁）、回滚能力复核（执行前重估），并在门禁失败时
 * 做门禁专属补偿（审批超时置 expired）。
 *
 * <p>口径（§3.5）：</p>
 * <ul>
 *   <li>{@link #approvedAt}：工单审批记录中 result=APPROVED 的 decidedAt 最大值；
 *       无 APPROVED 记录返回 null（时效门禁跳过）；查询异常 log.warn 后按 null 处理；</li>
 *   <li>{@link #rollbackStillAvailable}：instanceDao 取方言 → parser 解析 unit 明文 →
 *       ruleEngine 首轮审核取该语句命中 → assessor 重估，coverage != NONE 视为仍可回滚；
 *       <strong>任何异常 → log.warn + false（安全侧：复核不了就阻断执行）</strong>；</li>
 *   <li>{@link #onGateFailure}：APPROVAL_TTL 失败时把工单 approved → expired
 *       （§3.5「置 expired，需重新审批」），失败只记 warn 不阻断引擎的 unit 置 failed 流程。</li>
 * </ul>
 */
public class WorkerGateContext implements GateContext {

    private static final Logger log = LoggerFactory.getLogger(WorkerGateContext.class);

    private final ApprovalDao approvalDao;
    private final TicketDao ticketDao;
    private final InstanceDao instanceDao;
    private final SqlParserService parser;
    private final RuleEngine ruleEngine;
    private final RollbackCapabilityAssessor assessor;
    private final TableMetadataProvider metadata;

    public WorkerGateContext(ApprovalDao approvalDao, TicketDao ticketDao, InstanceDao instanceDao,
                             SqlParserService parser, RuleEngine ruleEngine,
                             RollbackCapabilityAssessor assessor, TableMetadataProvider metadata) {
        this.approvalDao = Objects.requireNonNull(approvalDao, "approvalDao");
        this.ticketDao = Objects.requireNonNull(ticketDao, "ticketDao");
        this.instanceDao = Objects.requireNonNull(instanceDao, "instanceDao");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.ruleEngine = Objects.requireNonNull(ruleEngine, "ruleEngine");
        this.assessor = Objects.requireNonNull(assessor, "assessor");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
    }

    @Override
    public LocalDateTime approvedAt(TicketUnit unit) {
        try {
            return approvalDao.listByTicket(unit.getTicketId()).stream()
                    .filter(r -> ApprovalResult.APPROVED == r.getResult())
                    .map(Approval::getDecidedAt)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
        } catch (Exception e) {
            // 审批时间查不到就按无审批处理：时效门禁不生效，结构漂移 / 回滚能力门禁仍兜底
            log.warn("[gate] 查询工单审批通过时间失败（按无 APPROVED 处理）: ticket={}",
                    unit.getTicketId(), e);
            return null;
        }
    }

    @Override
    public boolean rollbackStillAvailable(TicketUnit unit) {
        // 元数据路由：复核按 unit 实例取真实表结构
        InstanceContext.set(unit.getInstanceId());
        try {
            Instance inst = instanceDao.findById(unit.getInstanceId());
            if (inst == null) {
                // 实例已不存在（如执行前被删除）：无法复核 → 安全侧阻断
                log.warn("[gate] 实例缺失，回滚能力复核阻断: unit={} instance={}",
                        unit.getId(), unit.getInstanceId());
                return false;
            }
            Dialect dialect = dialectOf(inst);
            String sql = new String(unit.getStatement(), StandardCharsets.UTF_8);
            List<ParsedStatement> parsed = parser.parse(sql, dialect);
            if (parsed.isEmpty()) {
                log.warn("[gate] unit 语句解析为空（安全侧阻断执行）: unit={}", unit.getId());
                return false;
            }
            ParsedStatement ps = parsed.get(0);
            List<RuleHit> hits = ruleEngine.audit(List.of(ps)).hitsOf(0);
            RollbackPlan plan = assessor.assess(new CapabilityContext(ps, hits, metadata));
            return plan.getCoverage() != RollbackCoverage.NONE;
        } catch (Exception e) {
            // 安全侧：复核不了（实例缺失 / 解析失败 / 元数据异常）就阻断执行，绝不放行
            log.warn("[gate] 回滚能力复核失败（安全侧阻断执行）: unit={}", unit.getId(), e);
            return false;
        } finally {
            InstanceContext.set(null);
        }
    }

    @Override
    public void onGateFailure(TicketUnit unit, ExecutionGate.GateResult result) {
        if (result.failedGate() != GateCode.APPROVAL_TTL) {
            return;
        }
        try {
            // §3.5：审批超时 → 置 expired，需重新审批（expired 为终态，重新走审批 = 新工单）
            ticketDao.updateStatus(unit.getTicketId(), TicketStatus.APPROVED, TicketStatus.EXPIRED,
                    LocalDateTime.now());
        } catch (Exception e) {
            log.warn("[gate] 审批超时置 expired 失败: ticket={}", unit.getTicketId(), e);
        }
    }

    private static Dialect dialectOf(Instance inst) {
        // 实例方言写入时已校验为 Dialect 枚举，未知取值到不了这里；仅保留 null 兜底
        return inst == null || inst.getDialect() == null ? Dialect.MYSQL : inst.getDialect();
    }
}
