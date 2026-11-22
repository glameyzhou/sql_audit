package com.sqlaudit.executor.gate;

import com.sqlaudit.storage.model.TicketUnit;

import java.time.LocalDateTime;

/**
 * 执行前门禁的上下文提供者（§3.5 装配钩子）。
 *
 * <p>{@link ExecutionGate} 只做比对与判定，门禁所需的外部数据（审批时间、回滚能力复核）
 * 由本接口的装配实现提供：worker 侧装配 {@code WorkerGateContext}，测试用内存 fake。</p>
 */
public interface GateContext {

    /**
     * 工单审批通过时间（§3.5 审批时效门禁的数据源）。
     *
     * @param unit 执行单元
     * @return 该 unit 所属工单最近一次 APPROVED 决策时间；无审批通过记录返回 null
     */
    LocalDateTime approvedAt(TicketUnit unit);

    /**
     * 回滚能力复核（§3.5）：执行前重估可回滚性。
     *
     * @param unit 执行单元
     * @return true = 仍可回滚（覆盖非 NONE）；false = 回滚能力缺失，门禁阻断
     */
    boolean rollbackStillAvailable(TicketUnit unit);

    /**
     * 门禁失败后的补偿动作（如审批超时把工单置 EXPIRED，§3.5「置 expired，需重新审批」）。
     *
     * <p>引擎在回调之后将 unit 置 failed；本回调只负责门禁专属的补偿（默认不动作，
     * 实现方按门禁类型选择性地做）。</p>
     */
    default void onGateFailure(TicketUnit unit, ExecutionGate.GateResult result) {
    }
}
