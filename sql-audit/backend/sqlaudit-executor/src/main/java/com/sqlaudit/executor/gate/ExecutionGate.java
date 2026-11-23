package com.sqlaudit.executor.gate;

import com.sqlaudit.common.enums.GateCode;
import com.sqlaudit.storage.model.TicketUnit;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 执行前门禁（设计文档 §3.5）——审核通过到真正执行之间世界可能已经变了。
 *
 * <p>四道门禁，任一不过则<strong>不执行</strong>：</p>
 * <table>
 *   <tr><th>门禁</th><th>校验</th><th>不过时的动作</th></tr>
 *   <tr><td>并发互斥</td><td>exec_lock（引擎内置，不在本类）</td>
 *       <td>释放租约退回 pending</td></tr>
 *   <tr><td>结构漂移</td><td>ddl_fingerprint = SHA256(SHOW CREATE TABLE) 重算比对</td>
 *       <td>R_SCHEMA_DRIFT → unit 置 failed，工单退回重审，告警</td></tr>
 *   <tr><td>回滚能力复核</td><td>主键 / 触发器 / 外键 CASCADE 是否变化</td>
 *       <td>回滚能力降级 → 按 R_ROLLBACK_UNAVAILABLE 策略阻断</td></tr>
 *   <tr><td>审批时效</td><td>approved 未超 approval_ttl（默认 24h）</td>
 *       <td>置 expired，需重新审批</td></tr>
 * </table>
 *
 * <p>结构指纹的<strong>重算</strong>需要连库（ro_meta 查 SHOW CREATE TABLE /
 * pg_get_tabledef），本类只做比对与判定；取指纹的连接抽象为
 * {@link StructureFingerprintProvider}，生产实现 W2 装配。</p>
 */
public class ExecutionGate {

    /** 审批有效期默认 24h（§3.5）。 */
    public static final Duration DEFAULT_APPROVAL_TTL = Duration.ofHours(24);

    /** 指纹提供者：按实例 + 表重算当前结构指纹（ro_meta 只读连接）。 */
    public interface StructureFingerprintProvider {

        /** @return 当前结构指纹；表不存在返回 null */
        String currentFingerprint(long instanceId, String schema, String table);
    }

    /** 门禁结果：{@code failedGate} 为未通过的门禁（见 {@link GateCode}），通过时为 null。 */
    public record GateResult(boolean pass, GateCode failedGate, String reason) {

        public static GateResult ok() {
            return new GateResult(true, null, null);
        }

        public static GateResult fail(GateCode gate, String reason) {
            return new GateResult(false, gate, reason);
        }

        public boolean isPassed() {
            return pass;
        }
    }

    private final StructureFingerprintProvider fingerprintProvider;

    public ExecutionGate(StructureFingerprintProvider fingerprintProvider) {
        this.fingerprintProvider = fingerprintProvider;
    }

    /**
     * 全部门禁检查（并发互斥除外——引擎内 exec_lock 已保证）。
     *
     * @param unit           执行单元（含审核期记录的 ddl_fingerprint）
     * @param approvedAt     审批通过时间
     * @param rollbackStillAvailable 回滚能力复核结果（调用方用 rule/rollback 模块重估）
     * @param now            当前时间
     */
    public GateResult check(TicketUnit unit,
                            LocalDateTime approvedAt,
                            boolean rollbackStillAvailable,
                            LocalDateTime now) {
        // 门禁 1：审批时效（approved → 执行窗口最长 24h）
        if (approvedAt != null && approvedAt.plus(DEFAULT_APPROVAL_TTL).isBefore(now)) {
            return GateResult.fail(GateCode.APPROVAL_TTL,
                    "审批已超 " + DEFAULT_APPROVAL_TTL.toHours() + "h 有效期，需重新审批");
        }

        // 门禁 2：结构漂移（§3.5：审核后手工 ALTER 过表 → R_SCHEMA_DRIFT 拦截）
        if (unit.getDdlFingerprint() != null && unit.getObjectName() != null) {
            String current = fingerprintProvider.currentFingerprint(
                    unit.getInstanceId(), unit.getSchemaName(), unit.getObjectName());
            if (current == null || !current.equals(unit.getDdlFingerprint())) {
                return GateResult.fail(GateCode.SCHEMA_DRIFT,
                        "表结构与审核时不一致（" + unit.getObjectName() + "），退回重审");
            }
        }

        // 门禁 3：回滚能力复核（主键/触发器/外键 CASCADE 变化 → 阻断）
        if (!rollbackStillAvailable) {
            return GateResult.fail(GateCode.ROLLBACK_UNAVAILABLE,
                    "回滚能力较审核时降级，按策略阻断");
        }

        return GateResult.ok();
    }
}
