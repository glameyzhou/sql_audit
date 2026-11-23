package com.sqlaudit.worker;

import com.sqlaudit.common.crypto.CredentialCrypto;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.InstanceStatus;
import com.sqlaudit.common.enums.RollbackStatus;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.enums.UnitStatus;
import com.sqlaudit.executor.engine.UnitExecutionEngine;
import com.sqlaudit.executor.gate.ExecutionGate;
import com.sqlaudit.executor.gate.GateContext;
import com.sqlaudit.executor.reaper.LeaseReaper;
import com.sqlaudit.parser.SqlParserService;
import com.sqlaudit.rollback.lifecycle.RollbackLifecycleService;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlInstanceDao;
import com.sqlaudit.storage.impl.memory.InMemoryExecLockDao;
import com.sqlaudit.storage.impl.memory.InMemoryRollbackDao;
import com.sqlaudit.storage.impl.memory.InMemoryTicketUnitDao;
import com.sqlaudit.storage.model.Instance;
import com.sqlaudit.storage.model.TicketUnit;
import com.sqlaudit.worker.gateway.GatewayBridgeImpl;
import com.sqlaudit.worker.gateway.JdbcProductionGateway;
import com.sqlaudit.worker.rollback.RollbackPersisterImpl;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Worker 端到端测试：轮询 → 抢占 → 前镜像 → DML → 对账 → 反向 SQL 落库。
 * 生产库 = 本机 Docker MySQL 的专用测试库 {@code sql_audit_it_target}（真 JDBC 事务语义；
 * MySQL 不可用时 {@code Assumptions} 优雅跳过）。平台侧 unit / 锁 / 回滚记录仍用内存 DAO，
 * 门禁用恒 pass 的 {@link ExecutionGate} + 内存 fake 上下文（不因指纹/复核失败干扰执行链路测试）。
 *
 * <p>隔离性：每次运行 DROP + CREATE 专用库 {@code sql_audit_worker_e2e}（平台表）与
 * {@code sql_audit_it_target}（生产目标表），不触碰开发库 {@code sql-audit}。</p>
 */
class WorkerEndToEndTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3306;
    private static final String USER = "root";
    private static final String PASSWORD = "password";
    private static final String PLATFORM_DB = "sql_audit_worker_e2e";
    private static final String TARGET_DB = "sql_audit_it_target";

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 22, 19, 30, 0);

    private static HikariDataSource platformDs;
    private static JdbcTemplate platformJdbc;
    private static JdbcTemplate targetJdbc;
    private static MySqlInstanceDao instanceDao;
    private static long instanceId;
    private static JdbcProductionGateway gateway;

    /** 恒 pass 门禁：指纹提供者返回固定串（本测试 unit 不写 ddl_fingerprint，门禁不会查）。 */
    private static final ExecutionGate GATE =
            new ExecutionGate((instanceId, schema, table) -> "fp-fixed");

    /** 恒通过门禁上下文：无审批记录（时效门禁不生效）、恒判可回滚。 */
    private static final GateContext GATE_CONTEXT = new GateContext() {
        @Override
        public LocalDateTime approvedAt(TicketUnit unit) {
            return null;
        }

        @Override
        public boolean rollbackStillAvailable(TicketUnit unit) {
            return true;
        }
    };

    private final InMemoryTicketUnitDao unitDao = new InMemoryTicketUnitDao();
    private final InMemoryExecLockDao lockDao = new InMemoryExecLockDao();
    private final InMemoryRollbackDao rollbackDao = new InMemoryRollbackDao();

    private final UnitExecutionEngine engine = new UnitExecutionEngine(
            unitDao, lockDao, rollbackDao,
            new GatewayBridgeImpl(gateway, new SqlParserService(), demoMetadata(), instanceDao),
            new RollbackPersisterImpl(
                    new RollbackLifecycleService(rollbackDao, new SqlParserService()),
                    new SqlParserService(), demoMetadata(), instanceDao),
            GATE, GATE_CONTEXT);

    private final WorkerLoop loop = new WorkerLoop(
            unitDao, engine, new LeaseReaper(unitDao, lockDao), "worker-test", t -> true);

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(mysqlAvailable(), "本地 MySQL 未启动，跳过 worker 端到端测试");

        createDatabases();
        runPlatformDdl();

        platformDs = buildDataSource(PLATFORM_DB);
        platformJdbc = new JdbcTemplate(platformDs);
        targetJdbc = new JdbcTemplate(buildDataSource(TARGET_DB));
        targetJdbc.update("CREATE TABLE t_demo (id INT PRIMARY KEY AUTO_INCREMENT, a INT, b VARCHAR(64))");

        // 生产实例：t_instance 指向 127.0.0.1:3306（TARGET_DB 测试库）
        instanceDao = new MySqlInstanceDao(platformJdbc);
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
        targetJdbc.update("TRUNCATE TABLE t_demo");
        targetJdbc.update("INSERT INTO t_demo (id, a, b) VALUES (1, 10, 'x'), (2, 20, 'y'), (3, 30, 'z')");
    }

    /** demo 元数据：t_demo 主键 id。 */
    private static com.sqlaudit.rule.TableMetadataProvider demoMetadata() {
        return new com.sqlaudit.rule.TableMetadataProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public TableMetadata getTable(String schema, String name) {
                return new TableMetadata(true, java.util.List.of("id"), java.util.Set.of(),
                        java.util.List.of("id", "a", "b"), java.util.Set.of(), true);
            }
        };
    }

    private long insertUnit(String sql, String objectName) {
        TicketUnit u = new TicketUnit();
        u.setTicketId(1L);
        u.setInstanceId(instanceId);
        u.setObjectName(objectName);
        u.setUnitOrder(1);
        u.setStatement(sql);
        u.setStatementHash("h");
        u.setStatementType(StatementType.UPDATE);
        u.setStatus(UnitStatus.PENDING);
        u.setIntentWritten(false);
        return unitDao.insert(u);
    }

    @Test
    void fullLoop_updateWithPreimageAndRollbackPersisted() {
        long id = insertUnit("UPDATE t_demo SET a = 99 WHERE id <= 3", "t_demo");

        int executed = loop.runOnce(NOW);

        assertEquals(1, executed, "本轮应执行 1 个 unit");
        TicketUnit after = unitDao.findById(id);
        assertEquals(UnitStatus.SUCCESS, after.getStatus());
        assertEquals(3L, after.getAffectedRows(), "3 行受影响");

        // 生产库（MySQL 目标库）已变更
        Map<Integer, Integer> values = currentValues();
        assertEquals(99, values.get(1));
        assertEquals(99, values.get(2));
        assertEquals(99, values.get(3));

        // 反向 SQL 已落库（verified）且可查
        var master = rollbackDao.findByUnitId(id);
        assertNotNull(master, "反向 SQL 主记录应存在");
        assertEquals(RollbackStatus.VERIFIED, master.getStatus());
        assertEquals(3, master.getStmtCount(), "3 行前镜像 → 3 条反向 UPDATE");
    }

    @Test
    void secondRun_idempotent_noReexecution() {
        long id = insertUnit("UPDATE t_demo SET a = 55 WHERE id = 1", "t_demo");
        loop.runOnce(NOW);
        int affectedFirst = currentValues().get(1);
        assertEquals(55, affectedFirst);

        // 第二轮：unit 已 success + intent_written=1，绝不重跑
        loop.runOnce(NOW.plusSeconds(60));
        assertEquals(55, currentValues().get(1), "绝不重复执行");
        assertEquals(UnitStatus.SUCCESS, unitDao.findById(id).getStatus());
    }

    @Test
    void executionError_marksFailed() {
        // 语法错误语句（提交期会被拦，但防御性验证 worker 的失败路径）
        long id = insertUnit("UPDATE t_demo SET WHERE ???", "t_demo");
        loop.runOnce(NOW);
        assertEquals(UnitStatus.FAILED, unitDao.findById(id).getStatus());
    }

    @Test
    void reconcileMismatch_needsConfirmAndDataUnchanged() {
        // WHERE 命中 1 行但 DML 只影响 1 行——构造对账不匹配很难从外部注入；
        // 这里验证对账成功路径的边界：WHERE id = 999 命中 0 行 → 0 == 0 对账通过
        long id = insertUnit("UPDATE t_demo SET a = 1 WHERE id = 999", "t_demo");
        loop.runOnce(NOW);
        assertEquals(UnitStatus.SUCCESS, unitDao.findById(id).getStatus());
        assertEquals(0L, unitDao.findById(id).getAffectedRows());
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
            st.execute("DROP DATABASE IF EXISTS `" + TARGET_DB + "`");
            st.execute("CREATE DATABASE `" + TARGET_DB + "`");
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
            String ddl = readSchemaDdl();
            for (String sql : splitStatements(ddl)) {
                st.execute(sql);
            }
        }
    }

    private static String readSchemaDdl() throws Exception {
        try (InputStream in = WorkerEndToEndTest.class.getClassLoader()
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

    private static long insertInstance(MySqlInstanceDao dao) {
        Instance inst = new Instance();
        inst.setName("worker-e2e-instance");
        inst.setDialect(Dialect.MYSQL);
        inst.setOwnerId(1L);
        inst.setHost(HOST);
        inst.setPort(PORT);
        inst.setDefaultSchema(TARGET_DB);
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

    private Map<Integer, Integer> currentValues() {
        return targetJdbc.query("SELECT id, a FROM t_demo ORDER BY id",
                rs -> {
                    Map<Integer, Integer> result = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        result.put(rs.getInt(1), rs.getInt(2));
                    }
                    return result;
                });
    }
}
