package com.sqlaudit.server.service;

import com.sqlaudit.common.enums.AccountType;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.storage.impl.memory.InMemoryCredentialDao;
import com.sqlaudit.storage.impl.memory.InMemoryInstanceDao;
import com.sqlaudit.storage.model.Credential;
import com.sqlaudit.storage.model.Instance;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcQueryConnectionProvider} 单测：只覆盖不触网的前置校验分支
 * （实例缺失 / 凭据缺失 / IoTDB），以及 RO_META 回落路径的账号选择。
 */
class JdbcQueryConnectionProviderTest {

    private final InMemoryInstanceDao instanceDao = new InMemoryInstanceDao();
    private final InMemoryCredentialDao credentialDao = new InMemoryCredentialDao();
    private final JdbcQueryConnectionProvider provider =
            new JdbcQueryConnectionProvider(instanceDao, credentialDao);

    @Test
    void missingInstance_reportsInstanceNotFound() {
        SQLException e = assertThrows(SQLException.class, () -> provider.getConnection(999L));
        assertTrue(e.getMessage().contains("实例不存在"), e.getMessage());
    }

    @Test
    void instanceWithoutCredential_reportsMissingCredential() {
        long id = instanceDao.insert(instance(Dialect.MYSQL));

        SQLException e = assertThrows(SQLException.class, () -> provider.getConnection(id));
        assertTrue(e.getMessage().contains("缺少查询凭据"), e.getMessage());
    }

    @Test
    void iotdbInstance_rejected() {
        long id = instanceDao.insert(instance(Dialect.IOTDB));

        SQLException e = assertThrows(SQLException.class, () -> provider.getConnection(id));
        assertTrue(e.getMessage().contains("IoTDB"), e.getMessage());
    }

    @Test
    void withoutRoData_fallsBackToRoMeta_thenTriesRealConnection() {
        long id = instanceDao.insert(instance(Dialect.MYSQL));
        Credential credential = new Credential();
        credential.setInstanceId(id);
        credential.setAccountType(AccountType.RO_META);
        credential.setUsername("root");
        // 明文 "password" 的 AES-GCM 密文（开发默认密钥，仅测试）
        credential.setPasswordEnc(com.sqlaudit.common.crypto.CredentialCrypto.encrypt("password"));
        credentialDao.insert(credential);

        // 端口 1 必然连不上：断言失败原因来自「建连」而不是「缺凭据」，证明 RO_META 回落生效
        SQLException e = assertThrows(SQLException.class, () -> provider.getConnection(id));
        assertFalse(e.getMessage().contains("缺少查询凭据"), e.getMessage());
    }

    private static Instance instance(Dialect dialect) {
        Instance instance = new Instance();
        instance.setName("it-" + dialect);
        instance.setDialect(dialect);
        instance.setHost("127.0.0.1");
        instance.setPort(1);
        instance.setDefaultSchema("db1");
        instance.setOwnerId(1L);
        instance.setCreatedBy(1L);
        return instance;
    }
}
