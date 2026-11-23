package com.sqlaudit.storage.impl.jdbc.mysql;

import com.sqlaudit.storage.impl.jdbc.SeedSql;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC DAO 集成测试基建（MySQL 方言）。
 *
 * <p><strong>目标库：</strong>本地 Docker MySQL（mysql:8.0，容器 xiangxin-mysql）上的
 * <strong>独立测试库 {@code sql_audit_it}</strong>——不存在则自动创建，
 * <strong>绝不连接开发库 {@code sql-audit}</strong>（存储层 IT 曾经每个测试前 TRUNCATE
 * 开发库全部表，把本机开发数据清空，这个坑不允许再踩）。</p>
 *
 * <p><strong>生命周期：</strong>每个测试类在 {@code @BeforeAll} 里 {@link #migrate()}
 * （Flyway clean + migrate，跑真实的 V1__schema.sql + V2__seed.sql + 后续迁移，与生产同一套迁移文件）；
 * 每个测试方法在 {@code @BeforeEach} 里 {@link #cleanAndSeed}（清空全部业务表 + 重放种子）。
 * 表名从库元数据动态读取（排除 {@code flyway_schema_history}），不再手工维护表清单。</p>
 *
 * <p>MySQL 未启动时测试类用 {@link #mysqlAvailable()} + Assumptions 优雅跳过。</p>
 */
public final class MySqlTestSupport {

    public static final String DB_NAME = "sql_audit_it";
    public static final String DB_USER = "root";
    public static final String DB_PASSWORD = "password";

    /** 独立测试库连接串（connectTimeout 保证无库时快速失败以便 Assumptions 跳过）。 */
    public static final String JDBC_URL =
            "jdbc:mysql://127.0.0.1:3306/" + DB_NAME
                    + "?connectTimeout=3000&socketTimeout=30000&useSSL=false&allowPublicKeyRetrieval=true";

    /** 不指定库的服务器级连接（建库 / 探活用）。 */
    private static final String SERVER_URL =
            "jdbc:mysql://127.0.0.1:3306/?connectTimeout=3000&socketTimeout=30000"
                    + "&useSSL=false&allowPublicKeyRetrieval=true";

    private static final String MIGRATION_LOCATION = "classpath:db/migration/mysql";
    private static final String SEED_DIALECT_DIR = "mysql";

    private MySqlTestSupport() {
    }

    /** 探测本地 MySQL 是否可用（供 Assumptions.assumeTrue 优雅跳过）。 */
    public static boolean mysqlAvailable() {
        try (Connection ignored = DriverManager.getConnection(SERVER_URL, DB_USER, DB_PASSWORD)) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /** 独立测试库不存在则创建（root 有权限，无需预建）。 */
    public static void ensureDatabase() throws SQLException {
        try (Connection conn = DriverManager.getConnection(SERVER_URL, DB_USER, DB_PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + DB_NAME + "` DEFAULT CHARSET utf8mb4");
        }
    }

    /** 建测试连接池（并发测试需要多连接，池上限 12）。 */
    public static HikariDataSource buildDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(JDBC_URL);
        config.setUsername(DB_USER);
        config.setPassword(DB_PASSWORD);
        config.setMaximumPoolSize(12);
        config.setConnectionTimeout(5000);
        return new HikariDataSource(config);
    }

    /**
     * 每个测试类一次：Flyway clean（清掉上一轮测试类的一切表与历史）+ migrate
     * （跑真实的 V1__schema.sql + V2__seed.sql + 后续迁移）。{@code cleanDisabled(false)} 是
     * Flyway 10 起 clean 的默认关闭开关，测试库里必须显式打开。
     */
    public static void migrate(HikariDataSource ds) {
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
    }

    /**
     * 每个测试方法前清库：从库元数据动态读出全部业务表（排除 {@code flyway_schema_history}）
     * 逐张 TRUNCATE，再重放 {@code V2__seed.sql}（创世块 / 角色 / 权限 / 授权 / 账号 / 规则 /
     * 审批流全部回到种子状态）+ V3 及以后迁移文件的 DML（新端点的权限绑定行等）。
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
        for (String sql : SeedSql.replayStatements(SEED_DIALECT_DIR)) {
            jdbc.execute(sql);
        }
    }

    /** 从库元数据读取全部基表名（排除 Flyway 历史表），按名字排序保证稳定。 */
    public static List<String> businessTables(JdbcTemplate jdbc) {
        // MySQL 的表清单走 catalog（= 库名），schema 为空
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
}
