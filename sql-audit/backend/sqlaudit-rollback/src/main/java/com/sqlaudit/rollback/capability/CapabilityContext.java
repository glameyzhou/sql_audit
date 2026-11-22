package com.sqlaudit.rollback.capability;

import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.common.model.RuleHit;
import com.sqlaudit.rule.TableMetadataProvider;

import java.util.List;

/**
 * 回滚能力评估上下文（提交预览阶段）。
 *
 * @param statement 归一化语句
 * @param hits      该 unit 的规则命中（A-2 产出——不可回滚判定的信号源：
 *                  R_NO_PK / R_NONDETERMINISTIC_SET / R_MULTI_TABLE_DML / R_LIMIT_ORDER_DML 等）
 * @param metadata  表元数据（主键列 / 生成列清单，构造前镜像与反向 INSERT 必需）
 */
public record CapabilityContext(
        ParsedStatement statement,
        List<RuleHit> hits,
        TableMetadataProvider metadata) {
}
