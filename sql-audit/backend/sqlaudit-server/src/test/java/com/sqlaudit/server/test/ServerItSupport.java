package com.sqlaudit.server.test;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 服务端集成测试基建（沿用存储层 IT 的约定，见阶段 A 的 MySqlTestSupport）。
 *
 * <p><strong>目标库：</strong>本地 Docker MySQL（容器 xiangxin-mysql）上的独立测试库
 * {@code sql_audit_it}——不存在则创建，<strong>绝不连接开发库 {@code sql-audit}</strong>。
 * 需要「生产目标库」的用例（提交 / 执行端到端）用 {@code sql_audit_it_target}。</p>
 *
 * <p><strong>生命周期：</strong>测试类经 {@link #mysqlAvailable()}（@EnabledIf 条件，在
 * Spring 上下文加载前执行）确保 MySQL 可用、库存在、schema 为最新 V1+V2（Flyway
 * clean + migrate，每个 JVM 一次）；每个测试方法前经 {@link #cleanAndSeed} 清空全部业务表
 * （表名从库元数据动态读取，排除 flyway_schema_history）并重放 V2__seed.sql 与 V3+ 迁移的 DML。清库重放后
 * 调用方需同步失效服务端缓存（权限快照 / 当前用户缓存）并重载审批流配置。</p>
 */
public final class ServerItSupport {

    public static final String DB_NAME = "sql_audit_it";
    public static final String TARGET_DB_NAME = "sql_audit_it_target";
    public static final String DB_USER = "root";
    public static final String DB_PASSWORD = "password";

    /** 不指定库的服务器级连接串（建库 / 探活用；connectTimeout 保证无库时快速失败）。 */
    private static final String SERVER_URL =
            "jdbc:mysql://127.0.0.1:3306/?connectTimeout=3000&socketTimeout=30000"
                    + "&useSSL=false&allowPublicKeyRetrieval=true";

    /** 独立测试库连接串。 */
    public static final String JDBC_URL =
            "jdbc:mysql://127.0.0.1:3306/" + DB_NAME
                    + "?connectTimeout=3000&socketTimeout=30000&useSSL=false&allowPublicKeyRetrieval=true";

    /** 生产目标库连接串（提交 / 执行端到端用例）。 */
    public static final String TARGET_JDBC_URL =
            "jdbc:mysql://127.0.0.1:3306/" + TARGET_DB_NAME
                    + "?connectTimeout=3000&socketTimeout=30000&useSSL=false&allowPublicKeyRetrieval=true";

    private static final String MIGRATION_LOCATION = "classpath:db/migration/mysql";

    private static volatile boolean migrated = false;

    private ServerItSupport() {
    }

    /**
     * MySQL 可用性探测 + 库准备（@EnabledIf 条件方法，上下文加载前执行）：
     * 服务器可达则幂等建库，并（每个 JVM 一次）Flyway clean + migrate 把 schema 刷到
     * 最新 V1+V2——服务端测试上下文启动时的 Flyway migrate 因此是 no-op。
     */
    public static boolean mysqlAvailable() {
        if (!serverReachable()) {
            return false;
        }
        try {
            ensureDatabase(DB_NAME);
            ensureDatabase(TARGET_DB_NAME);
        } catch (SQLException e) {
            return false;
        }
        synchronized (ServerItSupport.class) {
            if (!migrated) {
                freshMigrate();
                migrated = true;
            }
        }
        return true;
    }

    private static boolean serverReachable() {
        try (Connection ignored = DriverManager.getConnection(SERVER_URL, DB_USER, DB_PASSWORD)) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static void ensureDatabase(String name) throws SQLException {
        try (Connection conn = DriverManager.getConnection(SERVER_URL, DB_USER, DB_PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + name + "` DEFAULT CHARSET utf8mb4");
        }
    }

    /** Flyway clean + migrate（跑真实 V1 + V2 + 后续迁移文件）。 */
    private static void freshMigrate() {
        HikariDataSource ds = buildDataSource(JDBC_URL);
        try {
            Flyway.configure()
                    .dataSource(ds)
                    .locations(MIGRATION_LOCATION)
                    .cleanDisabled(false)
                    .load()
                    .clean();
            Flyway.configure()
                    .dataSource(ds)
                    .locations(MIGRATION_LOCATION)
                    .cleanDisabled(false)
                    .load()
                    .migrate();
        } finally {
            ds.close();
        }
    }

    private static HikariDataSource buildDataSource(String url) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(DB_USER);
        config.setPassword(DB_PASSWORD);
        config.setMaximumPoolSize(8);
        config.setConnectionTimeout(5000);
        return new HikariDataSource(config);
    }

    /**
     * 每个测试方法前清库：从库元数据动态读出全部业务表（排除 flyway_schema_history）
     * 逐张 TRUNCATE，再重放 V2__seed.sql（创世块 / 角色 / 权限 / 授权 / 账号 / 规则 /
     * 审批流全部回到种子状态）+ V3 及以后迁移文件的 DML（新端点的权限绑定行等——
     * V2 冻结不可改，绑定行写在新迁移里，重放必须一起带回，否则端点覆盖测试误报裸奔）。
     */
    public static void cleanAndSeed(JdbcTemplate jdbc) {
        List<String> tables = businessTables(jdbc);
        jdbc.execute((Connection conn) -> {
            try (Statement st = conn.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS=0");
                for (String table : tables) {
                    st.execute("TRUNCATE TABLE `" + table + "`");
                }
                st.execute("SET FOREIGN_KEY_CHECKS=1");
            }
            return null;
        });
        for (String sql : replayStatements()) {
            jdbc.execute(sql);
        }
    }

    /** 从库元数据读取全部基表名（排除 Flyway 历史表）。 */
    public static List<String> businessTables(JdbcTemplate jdbc) {
        return jdbc.execute((Connection conn) -> {
            List<String> names = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData()
                    .getTables(DB_NAME, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (!"flyway_schema_history".equalsIgnoreCase(name)) {
                        names.add(name);
                    }
                }
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
        });
    }

    /** 读 classpath 下的 MySQL V2__seed.sql 全文。 */
    private static String readSeed() {
        return readResource("db/migration/mysql/V2__seed.sql");
    }

    /** classpath 下 mysql 方言的 V3 及以后迁移文件（排除已冻结的 V1/V2），按名排序去重。 */
    private static List<String> postV2MigrationFiles() {
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(ServerItSupport.class.getClassLoader());
        try {
            Resource[] resources = resolver.getResources("classpath*:db/migration/mysql/V*.sql");
            TreeSet<String> names = new TreeSet<>();
            for (Resource resource : resources) {
                String name = resource.getFilename();
                if (name != null && !name.equals("V1__schema.sql") && !name.equals("V2__seed.sql")) {
                    names.add(name);
                }
            }
            List<String> paths = new ArrayList<>();
            for (String name : names) {
                paths.add("db/migration/mysql/" + name);
            }
            return paths;
        } catch (Exception e) {
            throw new IllegalStateException("扫描迁移文件失败: db/migration/mysql", e);
        }
    }

    /** 清库重放语句清单：V2 全文 + V3 及以后迁移文件的 DML（跳过 DDL，表结构由 Flyway 保留）。 */
    private static List<String> replayStatements() {
        List<String> out = new ArrayList<>(statements(readSeed()));
        for (String file : postV2MigrationFiles()) {
            for (String stmt : statements(readResource(file))) {
                String first = stmt.stripLeading().split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
                if (Set.of("INSERT", "UPDATE", "DELETE", "MERGE").contains(first)) {
                    out.add(stmt);
                }
            }
        }
        return out;
    }

    /** 读 classpath 资源全文（缺失 / 读失败直接抛非受检异常）。 */
    private static String readResource(String path) {
        try (InputStream in = ServerItSupport.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("classpath 下未找到 " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取文件失败: " + path, e);
        }
    }

    /** 按「行尾分号」拆分 SQL 文件为语句列表（约定与 V2__seed.sql 文件头一致）。 */
    private static List<String> statements(String sql) {
        List<String> statements = new ArrayList<>();
        for (String chunk : sql.split(";\\r?\\n")) {
            String stripped = stripComments(chunk).strip();
            if (!stripped.isEmpty()) {
                statements.add(stripped);
            }
        }
        return statements;
    }

    /** 去掉每行以 -- 开头的注释（种子文件只有整行注释）。 */
    private static String stripComments(String chunk) {
        StringBuilder sb = new StringBuilder();
        for (String line : chunk.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (!trimmed.startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** 生产目标库建演示表（提交 / 执行端到端用例）。TRUNCATE 重置自增，id 恒为 1、2。 */
    public static void ensureTargetTable() {
        try (Connection conn = DriverManager.getConnection(TARGET_JDBC_URL, DB_USER, DB_PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS t_demo "
                    + "(id INT PRIMARY KEY AUTO_INCREMENT, a INT, b VARCHAR(64))");
            st.execute("TRUNCATE TABLE t_demo");
            st.execute("INSERT INTO t_demo (a, b) VALUES (10, 'x'), (20, 'y')");
        } catch (SQLException e) {
            throw new IllegalStateException("目标库表准备失败: " + e.getMessage(), e);
        }
    }
}
