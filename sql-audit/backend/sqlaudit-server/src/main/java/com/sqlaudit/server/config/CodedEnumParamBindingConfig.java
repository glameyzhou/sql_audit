package com.sqlaudit.server.config;

import com.sqlaudit.common.enums.CodedEnum;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.format.FormatterRegistry;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

/**
 * 请求参数里的 {@link CodedEnum} 绑定：{@code @RequestParam} / {@code @PathVariable} /
 * 查询对象绑定的 {@code String → 枚举} 转换改走 {@link CodedEnum#fromCode}
 * （按存储码、大小写不敏感），与 JSON 请求体的 {@code @JsonCreator fromCode} 口径一致。
 *
 * <p>为什么需要它：Spring 默认的 String→Enum 转换是 {@code Enum.valueOf(常量名)}——大小写
 * 敏感且只认常量名，而 {@code UnitStatus} / {@code Environment} / {@code TicketStatus} 等
 * 的存储码是小写（{@code executing} / {@code prod}），前端按存储码传参
 * （如 {@code GET /api/executions?status=executing}），默认转换会直接 400。</p>
 *
 * <p>只对实现了 {@link CodedEnum} 的枚举生效，其余枚举维持 Spring 默认行为；空白字符串
 * 仍转为 null（与默认行为一致，配合 {@code @RequestParam(required = false)} 表示「未传」）。</p>
 */
@Configuration
public class CodedEnumParamBindingConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new StringToCodedEnumConverter());
    }

    /**
     * 与 Spring 内置的 {@code StringToEnumConverterFactory} 注册在同一个 (String, Enum) 类型对上，
     * 后注册者先匹配；{@link #matches} 只放行 {@link CodedEnum}，其余枚举继续走内置转换。
     */
    private static final class StringToCodedEnumConverter implements ConditionalGenericConverter {

        @Override
        public Set<ConvertiblePair> getConvertibleTypes() {
            return Set.of(new ConvertiblePair(String.class, Enum.class));
        }

        @Override
        public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
            return CodedEnum.class.isAssignableFrom(targetType.getObjectType());
        }

        @Override
        public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
            String code = (String) source;
            return StringUtils.hasText(code) ? toCodedEnum(targetType.getObjectType(), code) : null;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Object toCodedEnum(Class<?> targetType, String code) {
            return CodedEnum.fromCode((Class) targetType, code);
        }
    }
}
