package com.sqlaudit.worker.gateway;

import com.sqlaudit.common.crypto.CredentialCrypto;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.InstanceStatus;
import com.sqlaudit.executor.gateway.ProductionGateway;
import com.sqlaudit.executor.gateway.ProductionGateway.ExecutionResult;
import com.sqlaudit.executor.gateway.ProductionGateway.Outcome;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlInstanceDao;
import com.sqlaudit.storage.model.Instance;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产库真执行端到端测试（@Tag("it")）：以本机 Docker MySQL 作为生产实例，
 * 验证 {@link JdbcProductionGateway} 的实例路由 → 凭据读取 → 单事务前镜像 → DML →
 * 对账 → 主键取回全链路。
 *
 * <p><strong>前置</strong>：本地 MySQL（127.0.0.1:3306，root/password）。未启动时
 * {@code Assumptions} 优雅跳过，不许让无库环境测试红。</p>
 *
 * <p>隔离性：每次运行 DROP + CREATE 专用测试库 {@code sql_audit_gw}（平台库）与
 * {@code sql_audit_gw_prod}（生产实例库），不触碰 {@code sql-audit} 平台库。</p>
 */
@Tag("it")
class JdbcProductionGatewayIT {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3306;
    private static final String USER = "root";
    private static final String PASSWORD = "password";
    private static final String PLATFORM_DB = "sql_audit_gw";
    private static final String PROD_DB = "sql_audit_gw_prod";

    private static HikariDataSource platformDs;
    private static JdbcTemplate platformJdbc;
    private static JdbcTemplate prodJdbc;
    private static JdbcProductionGateway gateway;
    private static long instanceId;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(mysqlAvailable(), "本地 MySQL 未启动，跳过生产库集成测试");

        createDatabases();
        runPlatformDdl();

        platformDs = buildDataSource(PLATFORM_DB);
        platformJdbc = new JdbcTemplate(platformDs);
        prodJdbc = new JdbcTemplate(buildDataSource(PROD_DB));
        createProdTable();

        // 生产实例：t_instance 指向 127.0.0.1:3306（PROD_DB 测试库）
        MySqlInstanceDao instanceDao = new MySqlInstanceDao(platformJdbc);
        instanceId = insertInstance(instanceDao);
        insertCredential();

        gateway = new JdbcProductionGateway(instanceDao, platformJdbc);
    }

    @AfterAll
    static void tearDown() {
        if (platformDs != null) {
            platformDs.close();
        }
    }

    @BeforeEach
    void resetDemoTable() {
        prodJdbc.update("TRUNCATE TABLE t_gw_demo");
        prodJdbc.update("INSERT INTO t_gw_demo (id, a, b) VALUES (1, 10, 'x'), (2, 20, 'y'), (3, 30, 'z')");
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    void update_withPreimage_successAndReconcilePassed() {
        ExecutionResult result = gateway.executeInTransaction(new ProductionGateway.ExecutionRequest(
                "UPDATE t_gw_demo SET a = 99 WHERE id <= 3",
                "SELECT id, a FROM t_gw_demo WHERE id <= 3 FOR UPDATE",
                List.of("id", "a"),
                List.of("id"),
                instanceId));

        assertEquals(Outcome.SUCCESS, result.outcome());
        assertEquals(3L, result.affectedRows(), "affected = matched rows");
        assertNotNull(result.preimageRows(), "前镜像应返回");
        assertEquals(3, result.preimageRows().size(), "前镜像行数 == affected");

        assertEquals(99, prodJdbc.queryForObject("SELECT a FROM t_gw_demo WHERE id=1", Integer.class));
        assertEquals(99, prodJdbc.queryForObject("SELECT a FROM t_gw_demo WHERE id=2", Integer.class));
        assertEquals(99, prodJdbc.queryForObject("SELECT a FROM t_gw_demo WHERE id=3", Integer.class));
    }

    @Test
    void reconcileMismatch_rollsBackAndReturnsReconcileFailed() {
        // 前镜像命中 3 行，但 DML 只影响 1 行 → affected != preimage → 回滚
        ExecutionResult result = gateway.executeInTransaction(new ProductionGateway.ExecutionRequest(
                "UPDATE t_gw_demo SET a = 1 WHERE id = 1",
                "SELECT id, a FROM t_gw_demo WHERE id <= 3 FOR UPDATE",
                List.of("id", "a"),
                List.of("id"),
                instanceId));

        assertEquals(Outcome.RECONCILE_FAILED, result.outcome());
        assertEquals(1L, result.affectedRows());
        assertNotNull(result.errorMessage(), "应带对账失败原因");

        // 事务已回滚：数据不变
        assertEquals(10, prodJdbc.queryForObject("SELECT a FROM t_gw_demo WHERE id=1", Integer.class));
        assertEquals(20, prodJdbc.queryForObject("SELECT a FROM t_gw_demo WHERE id=2", Integer.class));
        assertEquals(30, prodJdbc.queryForObject("SELECT a FROM t_gw_demo WHERE id=3", Integer.class));
    }

    @Test
    void insert_retrievesGeneratedKey() {
        ExecutionResult result = gateway.executeInTransaction(new ProductionGateway.ExecutionRequest(
                "INSERT INTO t_gw_demo (a, b) VALUES (100, 'ins')",
                null,
                null,
                List.of("id"),
                instanceId));

        assertEquals(Outcome.SUCCESS, result.outcome());
        assertEquals(1L, result.affectedRows());
        assertNotNull(result.insertedPkValues(), "INSERT 应取回主键");
        assertEquals(1, result.insertedPkValues().size());

        Object pk = result.insertedPkValues().get(0);
        assertNotNull(pk);
        assertEquals(100, prodJdbc.queryForObject(
                "SELECT a FROM t_gw_demo WHERE id=?", Integer.class, pk));
    }

    @Test
    void missingInstance_returnsExecutionFailed() {
        ExecutionResult result = gateway.executeInTransaction(new ProductionGateway.ExecutionRequest(
                "UPDATE t_gw_demo SET a = 0 WHERE id = 1",
                "SELECT id, a FROM t_gw_demo WHERE id <= 3 FOR UPDATE",
                List.of("id", "a"),
                List.of("id"),
                999_999L));

        assertEquals(Outcome.EXECUTION_FAILED, result.outcome());
        assertTrue(result.errorMessage() != null && result.errorMessage().contains("实例不存在"),
                "应报实例不存在: " + result.errorMessage());
    }

    // ------------------------------------------------------------------
    // 基建
    // ------------------------------------------------------------------

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
        try (InputStream in = JdbcProductionGatewayIT.class.getClassLoader()
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

    private static void createProdTable() {
        prodJdbc.update("CREATE TABLE t_gw_demo (id INT PRIMARY KEY AUTO_INCREMENT, a INT, b VARCHAR(64))");
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

    private static long insertInstance(MySqlInstanceDao dao) {
        Instance inst = new Instance();
        inst.setName("gw-test-instance");
        inst.setDialect(Dialect.MYSQL);
        inst.setOwnerId(1L);
        inst.setHost(HOST);
        inst.setPort(PORT);
        inst.setDefaultSchema(PROD_DB);
        inst.setStatus(InstanceStatus.ACTIVE);
        inst.setCreatedBy(1L);
        return dao.insert(inst);
    }

    private static void insertCredential() {
        // 凭据加密存储：与网关解密端共用同一 CredentialCrypto 密钥（默认开发密钥）
        String passwordEnc = CredentialCrypto.encrypt(PASSWORD);
        platformJdbc.update(
                "INSERT INTO t_credential (instance_id, account_type, username, password_enc) "
                        + "VALUES (?, 'RW_EXEC', ?, ?)",
                instanceId, USER, passwordEnc);
    }
}
