package com.sqlaudit.worker.gateway;

import com.sqlaudit.common.crypto.CredentialCrypto;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.InstanceStatus;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlCredentialDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlInstanceDao;
import com.sqlaudit.storage.model.Instance;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构指纹提供者集成测试（@Tag("it")）：以本机 Docker MySQL 作为生产实例，验证
 * {@link JdbcStructureFingerprintProvider} 的 RO_META 凭据路由 → SHOW CREATE TABLE →
 * SHA-256 全链路。
 *
 * <p><strong>前置</strong>：本地 MySQL（127.0.0.1:3306，root/password）。未启动时
 * {@code Assumptions} 优雅跳过，不许让无库环境测试红。</p>
 *
 * <p>隔离性：每次运行 DROP + CREATE 专用测试库 {@code sql_audit_fp}（平台库）与
 * {@code sql_audit_fp_prod}（生产实例库），不触碰 {@code sql-audit} 平台库。</p>
 */
@Tag("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JdbcStructureFingerprintProviderIT {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3306;
    private static final String USER = "root";
    private static final String PASSWORD = "password";
    private static final String PLATFORM_DB = "sql_audit_fp";
    private static final String PROD_DB = "sql_audit_fp_prod";

    private static HikariDataSource platformDs;
    private static JdbcTemplate platformJdbc;
    private static JdbcTemplate prodJdbc;
    private static JdbcStructureFingerprintProvider provider;
    /** 有 RO_META 凭据的实例（正常路径）。 */
    private static long instanceId;
    /** 无 RO_META 凭据的实例（异常路径）。 */
    private static long noCredInstanceId;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(mysqlAvailable(), "本地 MySQL 未启动，跳过指纹提供者集成测试");

        createDatabases();
        runPlatformDdl();

        platformDs = buildDataSource(PLATFORM_DB);
        platformJdbc = new JdbcTemplate(platformDs);
        prodJdbc = new JdbcTemplate(buildDataSource(PROD_DB));

        MySqlInstanceDao instanceDao = new MySqlInstanceDao(platformJdbc);
        instanceId = insertInstance(instanceDao, "fp-test-instance");
        insertCredential(instanceId);
        noCredInstanceId = insertInstance(instanceDao, "fp-no-cred-instance");

        provider = new JdbcStructureFingerprintProvider(
                instanceDao, new MySqlCredentialDao(platformJdbc));
    }

    @AfterAll
    static void tearDown() {
        if (platformDs != null) {
            platformDs.close();
        }
    }

    /** 每用例重建空表：SHOW CREATE 的输出（含 AUTO_INCREMENT 计数）随数据变化，保持确定。 */
    @BeforeEach
    void resetFpTable() {
        prodJdbc.update("DROP TABLE IF EXISTS t_fp_demo");
        prodJdbc.update("CREATE TABLE t_fp_demo (id INT PRIMARY KEY AUTO_INCREMENT, a INT, b VARCHAR(64))");
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void createdTable_fingerprintEqualsManualSha256OfShowCreate() {
        String expected = sha256(showCreate());

        String fingerprint = provider.currentFingerprint(instanceId, PROD_DB, "t_fp_demo");

        assertNotNull(fingerprint);
        assertEquals(expected, fingerprint, "指纹应 == 手动重算的 SHA-256(SHOW CREATE TABLE)");
    }

    @Test
    @Order(2)
    void alterTable_fingerprintChanges() {
        String before = provider.currentFingerprint(instanceId, PROD_DB, "t_fp_demo");

        prodJdbc.update("ALTER TABLE t_fp_demo ADD COLUMN c INT");

        String after = provider.currentFingerprint(instanceId, PROD_DB, "t_fp_demo");
        assertNotNull(before);
        assertNotNull(after);
        assertNotEquals(before, after, "ALTER 后指纹必须变化");
        assertEquals(sha256(showCreate()), after, "变化后的指纹仍 == 手动重算");
    }

    @Test
    @Order(3)
    void droppedTable_returnsNull() {
        prodJdbc.update("DROP TABLE t_fp_demo");

        assertNull(provider.currentFingerprint(instanceId, PROD_DB, "t_fp_demo"),
                "表不存在应返回 null（门禁侧按结构漂移拦截）");
    }

    @Test
    @Order(4)
    void missingRoMetaCredential_throwsIllegalState() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> provider.currentFingerprint(noCredInstanceId, PROD_DB, "t_fp_demo"));
        assertTrue(ex.getMessage().contains("RO_META"), "应报缺 RO_META 凭据: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // 基建（与 JdbcProductionGatewayIT 同模板）
    // ------------------------------------------------------------------

    private static String showCreate() {
        return prodJdbc.queryForObject("SHOW CREATE TABLE t_fp_demo",
                (rs, i) -> rs.getString(2));
    }

    /** 与 provider 同口径的手动 SHA-256 hex。 */
    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static boolean mysqlAvailable() {
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":" + PORT
                        + "/?connectTimeout=3000&useSSL=false&allowPublicKeyRetrieval=true",
                USER, PASSWORD)) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static void createDatabases() throws SQLException {
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + PLATFORM_DB + "`");
            st.execute("CREATE DATABASE `" + PLATFORM_DB + "`");
            st.execute("DROP DATABASE IF EXISTS `" + PROD_DB + "`");
            st.execute("CREATE DATABASE `" + PROD_DB + "`");
        }
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":" + PORT
                        + "/?connectTimeout=3000&useSSL=false&allowPublicKeyRetrieval=true",
                USER, PASSWORD);
    }

    /** 平台库跑完整 V1 DDL（复用 storage 迁移脚本，保证 t_instance/t_credential 列与真实现一致）。 */
    private static void runPlatformDdl() throws Exception {
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":" + PORT + "/" + PLATFORM_DB
                        + "?connectTimeout=3000&useSSL=false&allowPublicKeyRetrieval=true",
                USER, PASSWORD);
             Statement st = c.createStatement()) {
            String ddl = readV1Ddl();
            for (String sql : splitStatements(ddl)) {
                st.execute(sql);
            }
        }
    }

    private static String readV1Ddl() throws Exception {
        try (InputStream in = JdbcStructureFingerprintProviderIT.class.getClassLoader()
                .getResourceAsStream("db/migration/mysql/V1__schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("classpath 下未找到 db/migration/mysql/V1__schema.sql");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 按 {@code ;换行} 拆分多语句 DDL（与 storage 测试基建同口径）。 */
    private static List<String> splitStatements(String ddl) {
        List<String> statements = new ArrayList<>();
        for (String chunk : ddl.split(";\\r?\\n")) {
            String sql = chunk.strip();
            if (!sql.isEmpty()) {
                statements.add(sql);
            }
        }
        return statements;
    }

    private static HikariDataSource buildDataSource(String db) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:mysql://" + HOST + ":" + PORT + "/" + db
                + "?connectTimeout=3000&socketTimeout=30000&useSSL=false&allowPublicKeyRetrieval=true");
        cfg.setUsername(USER);
        cfg.setPassword(PASSWORD);
        cfg.setMaximumPoolSize(4);
        cfg.setConnectionTimeout(5000);
        return new HikariDataSource(cfg);
    }

    private static long insertInstance(MySqlInstanceDao dao, String name) {
        Instance inst = new Instance();
        inst.setName(name);
        inst.setDialect(Dialect.MYSQL);
        inst.setOwnerId(1L);
        inst.setHost(HOST);
        inst.setPort(PORT);
        inst.setDefaultSchema(PROD_DB);
        inst.setStatus(InstanceStatus.ACTIVE);
        inst.setCreatedBy(1L);
        return dao.insert(inst);
    }

    private static void insertCredential(long targetInstanceId) {
        // 凭据加密存储：与 provider 解密端共用同一 CredentialCrypto 密钥（默认开发密钥）
        String passwordEnc = CredentialCrypto.encrypt(PASSWORD);
        platformJdbc.update(
                "INSERT INTO t_credential (instance_id, account_type, username, password_enc) "
                        + "VALUES (?, 'RO_META', ?, ?)",
                targetInstanceId, USER, passwordEnc);
    }
}
