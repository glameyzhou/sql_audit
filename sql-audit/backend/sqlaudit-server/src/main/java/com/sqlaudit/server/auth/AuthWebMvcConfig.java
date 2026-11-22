package com.sqlaudit.server.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 鉴权拦截器注册：拦截 {@code /api/**}，放行 {@code /api/auth/login}。
 *
 * <p>独立于 {@link AuthConfig}，避免「配置类构造注入拦截器 → 拦截器依赖同配置类的
 * JwtService @Bean」造成的循环依赖。</p>
 */
@Configuration
public class AuthWebMvcConfig implements WebMvcConfigurer {

    private final RoleAuthInterceptor roleAuthInterceptor;

    public AuthWebMvcConfig(RoleAuthInterceptor roleAuthInterceptor) {
        this.roleAuthInterceptor = roleAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(roleAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login");
    }
}
