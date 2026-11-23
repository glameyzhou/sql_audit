package com.sqlaudit.rule;

import com.sqlaudit.common.model.ParsedStatement;

/**
 * 影响面评估器接口（README §4.3，异步执行）。
 *
 * <p>生产实现：用 ro_data 账号在只读副本上按原 WHERE 执行 SELECT COUNT(*)，
 * 带 statement_timeout（默认 3s）；超时/失败 → {@link ImpactEstimate#unknown()}
 * 或 {@link ImpactEstimate#timeout()}，直接升高危人工评估。</p>
 *
 * <p>没有只读副本时的降级（README §4.3）：实例配置 replica_dsn 为空 → ① 实例 Owner
 * 预授权开关开启时允许主库 COUNT 且超时压到 1s；② 否则 unknown。
 * <strong>绝不默默回退到 EXPLAIN rows</strong>。</p>
 */
@FunctionalInterface
public interface ImpactEstimator {

    /** 评估一条语句的影响行数（实现必须自带超时控制）。 */
    ImpactEstimate estimate(ParsedStatement statement);
}
