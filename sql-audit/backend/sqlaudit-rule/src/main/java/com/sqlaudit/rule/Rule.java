package com.sqlaudit.rule;

import com.sqlaudit.common.model.RuleHit;

import java.util.List;

/**
 * 规则接口：一条内置规则的可执行判定（设计文档 §4.3）。
 *
 * <p>实现约束：</p>
 * <ul>
 *   <li>规则<strong>只读上下文</strong>，不修改 ParsedStatement；</li>
 *   <li>命中一条返回一个 {@link RuleHit}（severity 取规则定义，可被配置覆盖——
 *       但 nonOverridable 规则的 ERROR 不可降级，由 {@link RuleEngine} 统一守卫）；</li>
 *   <li>元数据不可用时（metadata.isAvailable()=false）依赖元数据的规则按
 *       「无法证明安全」保守判定。</li>
 * </ul>
 */
public interface Rule {

    /** 规则定义。 */
    RuleDefinition definition();

    /**
     * 判定。
     *
     * @return 命中返回 RuleHit 列表（可能多条，如命中多个对象）；未命中返回空
     */
    List<RuleHit> check(RuleContext ctx);

    /** 便捷构造命中。 */
    default RuleHit hit(String message) {
        return new RuleHit(definition().code(), definition().severity(), message);
    }

    /** 便捷构造命中（带附加信息）。 */
    default RuleHit hit(String message, java.util.Map<String, Object> extra) {
        RuleHit h = new RuleHit(definition().code(), definition().severity(), message);
        if (extra != null) {
            extra.forEach(h::withExtra);
        }
        return h;
    }
}
