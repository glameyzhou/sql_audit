package com.sqlaudit.parser;

import com.sqlaudit.common.model.ParsedStatement;

import java.util.List;

/**
 * 方言解析器接口（设计文档 §4.1 三方言解析策略）。
 *
 * <p>实现职责：多语句拆分（一条语句 = 一个 unit）、归一化到 {@link ParsedStatement}、
 * AST 白名单只读判定（§4.2）。</p>
 *
 * <p>通用铁律（§4.1）：解析失败 = 高危 = 必须人工审核，绝不「降级放行」——
 * 实现必须在解析失败时返回 {@code parseConfidence=0} 且 {@code isReadOnly=false}
 * 的单条结果，而不是抛异常或跳过。</p>
 */
public interface DialectParser {

    /**
     * 解析（含多语句拆分）。输入可能是分号分隔的多条语句，输出与语句一一对应。
     *
     * @param sql 原始 SQL 文本
     * @return 每条语句一个 {@link ParsedStatement}；整体解析失败时返回单条
     *         {@code parseConfidence=0} 的结果（铁律：不降级放行）
     */
    List<ParsedStatement> parse(String sql);
}
