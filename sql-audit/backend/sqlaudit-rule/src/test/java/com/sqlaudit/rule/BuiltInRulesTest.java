package com.sqlaudit.rule;

import com.sqlaudit.common.enums.RuleCode;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 内置规则清单与 {@link RuleCode} 的一一对应关系。
 *
 * <p>规则码在代码里以枚举引用（分级、回滚能力判定、执行前门禁都按规则码分支），清单
 * {@link BuiltInRules#ALL} 是规则的唯一出处：两边必须严格一致——枚举里有而清单里没有的规则码，
 * 按码取定义会得到 null；清单里有而枚举里没有的，就没法在代码里被引用。</p>
 */
class BuiltInRulesTest {

    @Test
    void everyRuleCodeHasExactlyOneDefinitionAndViceVersa() {
        List<RuleCode> codes = BuiltInRules.ALL.stream().map(RuleDefinition::code).toList();

        Set<RuleCode> distinct = new HashSet<>(codes);
        assertEquals(codes.size(), distinct.size(), "内置规则码重复");
        assertEquals(EnumSet.allOf(RuleCode.class), distinct, "RuleCode 与 BuiltInRules.ALL 必须是同一集合");

        for (RuleCode code : RuleCode.values()) {
            RuleDefinition definition = BuiltInRules.byCode(code);
            assertEquals(code, definition.code(), "byCode 应按码取到对应定义");
        }
    }
}
