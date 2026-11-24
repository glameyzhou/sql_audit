package com.sqlaudit.server.service;

import com.sqlaudit.common.crypto.CredentialCrypto;
import com.sqlaudit.common.enums.AccountType;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.IotdbSqlDialect;
import com.sqlaudit.query.channel.ConnectionProvider;
import com.sqlaudit.storage.dao.CredentialDao;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.model.Credential;
import com.sqlaudit.storage.model.Instance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 线上查询通道的真实连接提供者：按 {@code instanceId} 路由到目标库的只读账号。
 *
 * <p>这里是 {@code QueryChannel} 与生产库之间唯一的连接入口：查询与「库表浏览
 * （RO_META 真连）」打到同一个目标库，不再有演示库的语义分叉。</p>
 *
 * <p>账号选择：优先 {@link AccountType#RO_DATA}（只读数据账号，README §4.1 查询通道专用），
 * 实例没有 RO_DATA 时回落到 {@link AccountType#RO_META}（实例表单保存的同一组账号）。
 * 连接按查询现建现关（不落连接池），与 {@link JdbcMetadataService} 同口径。</p>
 */
public class JdbcQueryConnectionProvider implements ConnectionProvider {

    /** 连接登录超时（秒）。 */
    private static final int LOGIN_TIMEOUT_SECONDS = 5;

    /** IoTDB 2.x JDBC 驱动类（jar 无 SPI 注册，须 Class.forName 显式加载后 DriverManager 才能识别）。 */
    private static final String IOTDB_DRIVER_CLASS = "org.apache.iotdb.jdbc.IoTDBDriver";

    private final InstanceDao instanceDao;
    private final CredentialDao credentialDao;

    public JdbcQueryConnectionProvider(InstanceDao instanceDao, CredentialDao credentialDao) {
        this.instanceDao = instanceDao;
        this.credentialDao = credentialDao;
    }

    @Override
    public Connection getConnection(long instanceId) throws SQLException {
        Instance instance = instanceDao.findById(instanceId);
        if (instance == null) {
            throw new SQLException("实例不存在: id=" + instanceId);
        }
        Dialect dialect = instance.getDialect() == null ? Dialect.MYSQL : instance.getDialect();

        Credential credential = credentialDao.findByInstanceAndType(instanceId, AccountType.RO_DATA);
        if (credential == null) {
            credential = credentialDao.findByInstanceAndType(instanceId, AccountType.RO_META);
        }
        if (credential == null) {
            throw new SQLException("实例缺少查询凭据（RO_DATA / RO_META）: id=" + instanceId);
        }
        String password;
        try {
            password = CredentialCrypto.decrypt(credential.getPasswordEnc());
        } catch (Exception e) {
            throw new SQLException("查询凭据解密失败: " + e.getMessage(), e);
        }

        ensureDriverLoaded(dialect);
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        return DriverManager.getConnection(jdbcUrl(instance, dialect),
                credential.getUsername(), password);
    }

    private static String jdbcUrl(Instance instance, Dialect dialect) {
        String host = instance.getHost();
        int port = instance.getPort() == null ? defaultPort(dialect) : instance.getPort();
        String schema = instance.getDefaultSchema() == null ? "" : instance.getDefaultSchema();
        if (dialect == Dialect.PG) {
            return "jdbc:postgresql://" + host + ":" + port + "/" + schema
                    + "?connectTimeout=" + LOGIN_TIMEOUT_SECONDS + "&socketTimeout=30";
        }
        if (dialect == Dialect.IOTDB) {
            // IoTDB 2.x JDBC：URL 不带库名；树/表双模型是连接级方言开关（默认 tree），
            // 表模型（宽表）须显式 ?sql_dialect=table
            return instance.getIotdbSqlDialect() == IotdbSqlDialect.TABLE
                    ? "jdbc:iotdb://" + host + ":" + port + "?sql_dialect=table"
                    : "jdbc:iotdb://" + host + ":" + port + "/";
        }
        return "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&connectTimeout=" + (LOGIN_TIMEOUT_SECONDS * 1000) + "&socketTimeout=30000";
    }

    private static int defaultPort(Dialect dialect) {
        if (dialect == Dialect.PG) {
            return 5432;
        }
        if (dialect == Dialect.IOTDB) {
            return 6667;
        }
        return 3306;
    }

    /** IoTDB 驱动无 SPI 注册，Class.forName 触发其 static 块向 DriverManager 注册（幂等）。 */
    private static void ensureDriverLoaded(Dialect dialect) {
        if (dialect != Dialect.IOTDB) {
            return;
        }
        try {
            Class.forName(IOTDB_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("IoTDB JDBC 驱动未加载: " + IOTDB_DRIVER_CLASS, e);
        }
    }
}
