package com.sqlaudit.rule;

/**
 * 影响面评估结果（README §4.3）。
 *
 * <p><strong>口径（README §4.3 补充）</strong>：</p>
 * <ul>
 *   <li>影响面评估<strong>异步</strong>执行（COUNT(*) 超时 3s 装不进审核接口 P95 &lt; 500ms），
 *       规则审核同步返回；本对象在异步任务完成后作为二次评估输入；</li>
 *   <li>EXPLAIN 的 rows 是估算值，<strong>不得</strong>作为来源——只允许真实 COUNT(*)；</li>
 *   <li>估算值会过期：只用于风险分级，不作为执行期正确性依据（执行期由
 *       affected_rows 对账给出）；</li>
 *   <li>无只读副本且未授权主库 COUNT → {@code unknown=true}，风险等级升一级。</li>
 * </ul>
 */
public record ImpactEstimate(
        /** 预估影响行数（COUNT(*) 实测值） */
        Long estimatedRows,
        /** 影响面未知（副本缺失 / COUNT 超时 / 未授权），风险升一级 */
        boolean unknown,
        /** 评估超时（同样按 unknown 处理但区分原因） */
        boolean timeout) {

    public static ImpactEstimate of(long rows) {
        return new ImpactEstimate(rows, false, false);
    }

    public static ImpactEstimate ofUnknown() {
        return new ImpactEstimate(null, true, false);
    }

    public static ImpactEstimate ofTimeout() {
        return new ImpactEstimate(null, true, true);
    }

    public boolean isUnknown() {
        return unknown;
    }
}
