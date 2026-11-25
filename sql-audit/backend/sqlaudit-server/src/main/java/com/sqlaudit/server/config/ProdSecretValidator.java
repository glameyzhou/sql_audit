package com.sqlaudit.server.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 生产环境密钥强校验（仅 {@code prod} profile 生效）。
 *
 * <p>开发/测试环境允许密钥回落内置默认值以便开箱启动；生产环境严禁使用仓库内公开的
 * 默认开发密钥，缺失任一环境变量即在启动期 fail-fast 阻断。</p>
 */
@Component
@Profile("prod")
public class ProdSecretValidator implements InitializingBean {

    private static final List<String> REQUIRED_SECRETS = List.of(
            "SQLAUDIT_JWT_SECRET",
            "SQLAUDIT_CREDENTIAL_KEY",
            "SQLAUDIT_DETAIL_KEY",
            "SQLAUDIT_SQL_KEY"
    );

    private final Environment environment;

    public ProdSecretValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> missing = new ArrayList<>();
        for (String name : REQUIRED_SECRETS) {
            String value = environment.getProperty(name);
            if (value == null || value.isBlank()) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "prod 环境禁止使用内置开发默认密钥，必须通过环境变量注入: " + String.join(", ", missing));
        }
    }
}
