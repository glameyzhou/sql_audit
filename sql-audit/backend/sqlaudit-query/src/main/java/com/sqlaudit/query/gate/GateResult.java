package com.sqlaudit.query.gate;

import com.sqlaudit.common.model.ParsedStatement;

/**
 * 查询门禁判定结果（README §4.1 查询链路第一跳：parser 只读判定）。
 *
 * @param allowed         是否放行进入查询通道
 * @param reason          拒绝原因（放行时为 null）
 * @param parsedStatement 放行时的解析结果（含 limitAst / dangerousClauses 等），拒绝时为 null
 * @param limitInjectable LIMIT 注入层是否可用（IoTDB 无 Druid AST → false，走兜底层）
 */
public record GateResult(
        boolean allowed,
        String reason,
        ParsedStatement parsedStatement,
        boolean limitInjectable) {

    static GateResult reject(String reason) {
        return new GateResult(false, reason, null, false);
    }
}
