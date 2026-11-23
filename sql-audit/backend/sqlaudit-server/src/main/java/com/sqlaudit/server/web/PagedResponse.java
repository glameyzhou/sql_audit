package com.sqlaudit.server.web;

import java.util.List;

/**
 * 分页列表响应体（服务端分页端点统一形状）：{@code { items, total }}。
 *
 * <p>分页参数约定（各列表端点共用，契约 §6 的 page / size 查询参数）：</p>
 * <ul>
 *   <li>{@code page} 从 1 起，默认 1，越界（&lt;1）钳制为 1，不报错；</li>
 *   <li>{@code size} 默认 20，钳制到 [1, 200]（上限 200 防超大页），不报错。</li>
 * </ul>
 */
public record PagedResponse<T>(List<T> items, long total) {

    /** 构造：{@code items} 不允许为 null（空列表用 {@link List#of()}）。 */
    public static <T> PagedResponse<T> of(List<T> items, long total) {
        return new PagedResponse<>(items == null ? List.of() : items, total);
    }

    /** 默认每页条数（{@code @RequestParam(defaultValue = ...)} 用字符串常量）。 */
    public static final String DEFAULT_SIZE_STR = "20";

    /** 每页条数上限（钳制，不报错）。 */
    public static final int MAX_SIZE = 200;

    /** size 钳制到 [1, {@value #MAX_SIZE}]。 */
    public static int clampSize(int size) {
        return Math.max(1, Math.min(size, MAX_SIZE));
    }

    /**
     * 由 page（从 1 起，越界钳制为 1）与已钳制的 limit 计算 offset（从 0 起）。
     * 用 long 中间值防溢出，结果钳制到 int 上限。
     */
    public static int offsetOf(int page, int limit) {
        long offset = (long) Math.max(0, page - 1) * limit;
        return (int) Math.min(offset, Integer.MAX_VALUE);
    }
}
