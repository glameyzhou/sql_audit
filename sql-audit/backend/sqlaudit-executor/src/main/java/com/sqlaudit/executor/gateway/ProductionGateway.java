package com.sqlaudit.executor.gateway;

import java.util.List;
import java.util.Map;

/**
 * 生产库事务边界抽象（README §4.4 事务边界与方言差异）。
 *
 * <p><strong>这是整个系统正确性最敏感的接口</strong>。实现必须保证：</p>
 * <ol>
 *   <li><strong>同一 Connection、同一事务</strong>：前镜像 SELECT FOR UPDATE 与 DML
 *       在同一个事务内执行（README §4.4，v1 致命错误修正）；</li>
 *   <li><strong>隔离级别固定</strong>：PG 显式 REPEATABLE READ（SET TRANSACTION ISOLATION
 *       LEVEL），MySQL 保持默认 RR 且禁止降级 RC；每次执行前 assert 当前会话隔离级别
 *       （云厂商/中间件可能改默认值，README §4.4 实现要点）；</li>
 *   <li><strong>对账先行</strong>：COMMIT 前比对 affectedRows == preimageRows.size()，
 *       不等则回滚事务并返回 RECONCILE_FAILED（引擎层转 needs_confirm，README §4.4 硬门禁）；</li>
 *   <li>MySQL 前提：Connector/J 的 useAffectedRows 保持默认 false（getUpdateCount 返回
 *       matched rows 才能对账，README §4.4）；INSERT 无前镜像不做对账。</li>
 * </ol>
 *
 * <p>生产实现（W2）：rw_exec 账号连接池 + JDBC；单测用 fake 验证协议编排。
 * IoTDB 实现可声明不支持前镜像（快照 SOP，README §4.4）。</p>
 */
public interface ProductionGateway {

    /**
     * 在单个生产库事务内执行一条 DML（前镜像 → DML → 对账 → COMMIT/ROLLBACK）。
     *
     * @param request 执行请求
     * @return 执行结果（含前镜像与影响行数——回滚落库依赖）；对账失败或执行异常
     *         返回对应 outcome（事务已回滚）
     */
    ExecutionResult executeInTransaction(ExecutionRequest request);

    /** 执行请求。 */
    record ExecutionRequest(
            /** unit 原始语句（明文，由实现方解密） */
            String statementSql,
            /** 前镜像 SELECT（PreimagePlanner 产出；INSERT 为 null） */
            String preimageSql,
            /** 前镜像列序（参数对齐用；INSERT 为 null） */
            List<String> preimageColumns,
            /** 主键列（依赖检测 hash / 反向语句用；INSERT 取值路径为 LAST_INSERT_ID/RETURNING） */
            List<String> pkColumns,
            /** 实例 id（连接路由用） */
            long instanceId) {
    }

    /** 执行结果。 */
    record ExecutionResult(
            Outcome outcome,
            /** 前镜像行（Map&lt;列名, 旧值&gt;；INSERT 路径为 null） */
            List<Map<String, Object>> preimageRows,
            /** DML 实际影响行数（matched rows 口径，MySQL） */
            long affectedRows,
            /** INSERT 路径取到的主键值（LAST_INSERT_ID / RETURNING pk） */
            List<Object> insertedPkValues,
            /** 失败原因（outcome != SUCCESS 时） */
            String errorMessage) {

        public static ExecutionResult success(List<Map<String, Object>> preimage,
                                              long affectedRows, List<Object> insertedPk) {
            return new ExecutionResult(Outcome.SUCCESS, preimage, affectedRows, insertedPk, null);
        }

        public static ExecutionResult reconcileFailure(long preimageCount, long affectedCount) {
            return new ExecutionResult(Outcome.RECONCILE_FAILED, null, affectedCount, null,
                    "affected_rows(" + affectedCount + ") != preimage_rows(" + preimageCount
                            + ")，事务已回滚（硬门禁）");
        }

        public static ExecutionResult executionError(String message) {
            return new ExecutionResult(Outcome.EXECUTION_FAILED, null, 0, null, message);
        }
    }

    /** 执行结局。 */
    enum Outcome {
        /** 成功（对账通过，事务已 COMMIT） */
        SUCCESS,
        /** 对账失败（事务已 ROLLBACK，unit 须转 needs_confirm 人工核对——不能盲目重跑） */
        RECONCILE_FAILED,
        /** 执行异常（事务已 ROLLBACK，可安全重试） */
        EXECUTION_FAILED
    }
}
