package com.sqlaudit.common.model;

import com.sqlaudit.common.enums.RollbackCoverage;
import com.sqlaudit.common.enums.RollbackGenerator;
import com.sqlaudit.common.enums.UnavailableReason;

import java.util.ArrayList;
import java.util.List;

/**
 * 回滚计划（README §4.4）。
 *
 * <p>提交预览时给出「本单可回滚 / 不可回滚」清单（README §4.4 能力边界）。
 * 覆盖率 + 不可用原因，对应 {@code rollback_sql.coverage} / {@code unavailable_reason}。</p>
 *
 * <p>契约要点（README §4.4）：</p>
 * <ul>
 *   <li>生成时机：前镜像在 Worker 执行时刻、同一连接同一事务内采集，审核/审批阶段不提前生成；</li>
 *   <li>预览阶段只给出「回滚能力预判」，不落地前镜像值；</li>
 *   <li>覆盖率 NONE 的高危语句按策略拒绝或强制影子表/快照/binlog。</li>
 * </ul>
 */
public class RollbackPlan {

    private RollbackCoverage coverage = RollbackCoverage.NONE;

    /** 不可回滚时的具体原因（coverage=NONE 时非空） */
    private UnavailableReason unavailableReason;

    /** 生成器（对应 rollback_sql.generator；不可回滚的计划为 {@link RollbackGenerator#NONE}） */
    private RollbackGenerator generator;

    /** 反向语句预览（参数化、不含前镜像值；实际落库在 rollback_statement 子表） */
    private List<String> reverseStatementPreview = new ArrayList<>();

    /** 预估反向语句条数 */
    private int estimatedStatementCount;

    public RollbackCoverage getCoverage() {
        return coverage;
    }

    public void setCoverage(RollbackCoverage coverage) {
        this.coverage = coverage;
    }

    public UnavailableReason getUnavailableReason() {
        return unavailableReason;
    }

    public void setUnavailableReason(UnavailableReason unavailableReason) {
        this.unavailableReason = unavailableReason;
    }

    public RollbackGenerator getGenerator() {
        return generator;
    }

    public void setGenerator(RollbackGenerator generator) {
        this.generator = generator;
    }

    public List<String> getReverseStatementPreview() {
        return reverseStatementPreview;
    }

    public void setReverseStatementPreview(List<String> reverseStatementPreview) {
        this.reverseStatementPreview = reverseStatementPreview;
    }

    public int getEstimatedStatementCount() {
        return estimatedStatementCount;
    }

    public void setEstimatedStatementCount(int estimatedStatementCount) {
        this.estimatedStatementCount = estimatedStatementCount;
    }

    /** 是否完整可回滚。 */
    public boolean isFullyRollbackable() {
        return coverage == RollbackCoverage.FULL;
    }
}
