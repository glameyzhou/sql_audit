package com.sqlaudit.rule;

import com.sqlaudit.common.model.ParsedStatement;

import java.util.List;

/**
 * 一次审核的上下文（unit 级）。
 *
 * @param statement   本 unit 的归一化语句
 * @param metadata    表元数据提供者（规则按需查询）
 * @param impact      影响面评估结果（异步任务产物；首轮同步审核为 null）
 * @param ticketUnits 同一工单的全部 unit（工单级规则用，如 R_TX_DDL_MIX / R_BIG_UNIT_COUNT）
 */
public record RuleContext(
        ParsedStatement statement,
        TableMetadataProvider metadata,
        ImpactEstimate impact,
        List<ParsedStatement> ticketUnits) {
}
