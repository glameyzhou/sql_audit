package com.sqlaudit.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 角色的实例数据范围（对应 {@code t_role.instance_scope} 列）。
 *
 * <p>功能权限（角色 ↔ 权限点）回答「能不能调这个接口」，本枚举回答「能对哪些实例调」，
 * 两轴正交、都过才放行：</p>
 * <ul>
 *   <li>{@link #ALL}：该角色覆盖全部实例，不查 {@code t_role_instance}；</li>
 *   <li>{@link #SELECTED}：该角色只覆盖 {@code t_role_instance} 里显式授权的实例
 *       （一行都没有 = 一个实例都不覆盖）。</li>
 * </ul>
 *
 * <p>用户的有效实例集合 = 「授权本次请求的角色」里，任一 {@link #ALL} 则为全部，
 * 否则为这些角色已授权实例的并集（判定在服务层，见技术契约 §4.4）。</p>
 */
public enum InstanceScope implements CodedEnum {
    /** 全部实例 */
    ALL,
    /** 仅 t_role_instance 中显式授权的实例 */
    SELECTED;

    /** DB / JSON 存储码（= 枚举名）。 */
    @Override
    @JsonValue
    public String getCode() {
        return name();
    }

    /** 存储码 → 枚举（大小写不敏感；null 透传；未知取值抛 IllegalArgumentException）。 */
    @JsonCreator
    public static InstanceScope fromCode(String code) {
        return CodedEnum.fromCode(InstanceScope.class, code);
    }
}
