package com.sqlaudit.server.auth;

import com.sqlaudit.auth.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 认证装配：JWT 密钥 / bcrypt 编码器。
 *
 * <p>JWT 密钥走环境变量 {@code SQLAUDIT_JWT_SECRET}（默认值仅本地开发，≥16 字符）。
 * 拦截器注册见 {@link AuthWebMvcConfig}（拆分配置以避免与拦截器间的循环依赖）。</p>
 */
@Configuration
public class AuthConfig {

    @Bean
    public JwtService jwtService(
            @Value("${SQLAUDIT_JWT_SECRET:sql-audit-local-dev-secret-key}") String secret) {
        return new JwtService(secret);
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
