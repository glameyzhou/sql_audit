package com.sqlaudit.query.limit;

/**
 * LIMIT 注入结果（README §4.1 三层保护第 1 层）。
 *
 * <p>注入层失败（{@code unsupported=true}）不抛异常，调用方继续用原 SQL
 * 走 setMaxRows + 流式 cancel 兜底（第 2、3 层）。</p>
 *
 * <p>与 README §4.3「不改写用户 SQL」的边界：查询通道的 LIMIT 注入是保护性收紧，
 * 结果必须带 rewrittenSql 供前端展示「平台已注入 LIMIT N」，让用户知道自己跑的是什么。</p>
 *
 * @param rewrittenSql   实际执行的 SQL：注入/收紧时为改写后 SQL，否则与原 SQL 相同
 * @param originalLimit  用户原 LIMIT 行数；null 表示原 SQL 没有 LIMIT（或行数非整数字面量）
 * @param effectiveLimit 注入/收紧后实际生效的 LIMIT 行数；未改写时等于 originalLimit（可能为 null）
 * @param injected       平台是否注入了 LIMIT（原 SQL 无 LIMIT）
 * @param tightened      平台是否收紧了用户过大的 LIMIT（原 LIMIT &gt; 上限，改写为上限）
 * @param unsupported    注入层是否不可用（非 SELECT / 非 Druid 方言 / 解析失败 / 多语句），
 *                       true 时 rewrittenSql 与原 SQL 相同，由第 2、3 层兜底
 */
public record InjectionResult(
        String rewrittenSql,
        Integer originalLimit,
        Integer effectiveLimit,
        boolean injected,
        boolean tightened,
        boolean unsupported) {

    /** 注入层不可用：原样透传，交由 setMaxRows / 流式 cancel 兜底。 */
    static InjectionResult unsupported(String sql) {
        return new InjectionResult(sql, null, null, false, false, true);
    }

    /** 原样透传（用户 LIMIT 在上限内，或行数非整数字面量无法判定，交由兜底层）。 */
    static InjectionResult passthrough(String sql, Integer originalLimit) {
        return new InjectionResult(sql, originalLimit, originalLimit, false, false, false);
    }

    /** 结果页提示文案（README §4.1：必须展示「平台已注入 LIMIT N」）。 */
    public String notice() {
        if (injected) {
            return "平台已注入 LIMIT " + effectiveLimit;
        }
        if (tightened) {
            return "原 LIMIT " + originalLimit + " 超出上限，平台已收紧为 LIMIT " + effectiveLimit;
        }
        return null;
    }
}
