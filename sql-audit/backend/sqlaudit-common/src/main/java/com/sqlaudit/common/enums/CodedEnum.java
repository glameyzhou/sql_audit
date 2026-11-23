package com.sqlaudit.common.enums;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 带「存储码」的枚举：数据库列、JSON、请求参数里流转的是 {@link #getCode()}，
 * Java 代码里只用枚举常量。
 *
 * <p>为什么要有存储码：存量数据里同一类值的口径并不统一——工单 / 执行单元 / 回滚状态存小写
 * （{@code draft}、{@code executing}），用户 / 实例状态存大写（{@code ACTIVE}），环境存
 * {@code prod} 而审批流兜底是 {@code ALL}。枚举常量名统一大写，存储码各自带，
 * 存量数据与前端契约都不用动。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>存储码一经上线不可改、不可复用（DB 已有数据、前端已有展示依赖）；新增取值只在末尾追加；</li>
 *   <li>枚举与存储码互转<strong>只走本接口的 {@link #fromCode} / {@link #code}</strong>，
 *       业务代码里不再出现 {@code name().toLowerCase()}、{@code valueOf(x.toUpperCase())}
 *       这类手写转换；</li>
 *   <li>{@link #fromCode} 大小写不敏感、null 透传，未知取值抛 {@link IllegalArgumentException}
 *       ——拼错会在入口立刻暴露，不会被静默写进库。</li>
 * </ul>
 *
 * <p>实现类需自行声明带 {@code @JsonValue} 的 {@code getCode()} 与带 {@code @JsonCreator} 的
 * {@code static fromCode(String)}（Jackson 不读取接口方法上的注解）。</p>
 */
public interface CodedEnum {

    /** DB / JSON 存储码。 */
    String getCode();

    /**
     * 枚举 → 存储码（null 透传），JDBC 写参数用。
     */
    static String code(CodedEnum value) {
        return value == null ? null : value.getCode();
    }

    /**
     * 存储码 → 枚举。
     *
     * @param type 枚举类型
     * @param code 存储码（大小写不敏感，首尾空白忽略；null 返回 null）
     * @throws IllegalArgumentException 取值不在枚举内（消息里列出全部合法值）
     */
    static <E extends Enum<E> & CodedEnum> E fromCode(Class<E> type, String code) {
        if (code == null) {
            return null;
        }
        String value = code.trim();
        for (E e : type.getEnumConstants()) {
            if (e.getCode().equals(value)) {
                return e;
            }
        }
        for (E e : type.getEnumConstants()) {
            if (e.getCode().equalsIgnoreCase(value)) {
                return e;
            }
        }
        throw new IllegalArgumentException("未知的 " + type.getSimpleName() + " 取值: \"" + code
                + "\"，合法值: " + Arrays.stream(type.getEnumConstants())
                .map(CodedEnum::getCode).collect(Collectors.toList()));
    }
}
