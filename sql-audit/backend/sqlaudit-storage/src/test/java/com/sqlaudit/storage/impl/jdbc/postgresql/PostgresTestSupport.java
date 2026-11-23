package com.sqlaudit.storage.impl.jdbc.postgresql;

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
 * JDBC DAO 集成测试基建（PostgreSQL 方言）。
 *
 * <p><strong>目标库：</strong>本地 Docker PostgreSQL（postgres:16，容器 sqlaudit-pg）上的
 * <strong>独立测试库 {@code sql_audit_it}</strong>——不存在则自动创建，
 * <strong>绝不连接开发库 {@code sql-audit}</strong>（与 MySQL 侧同一坑位防复发）。</p>
 *
 * <p><strong>生命周期：</strong>每个测试类在 {@code @BeforeAll} 里 {@link #migrate()}
 * （Flyway clean + migrate，跑真实的 V1__schema.sql + V2__seed.sql + 后续迁移，与生产同一套迁移文件）；
 * 每个测试方法在 {@code @BeforeEach} 里 {@link #cleanAndSeed}（清空全部业务表 + 重放种子）。
 * 表名从库元数据动态读取（排除 {@code flyway_schema_history}），不再手工维护表清单。</p>
 *
 * <p>PG 未启动时测试类用 {@link #postgresAvailable()} + Assumptions 优雅跳过。</p>
 */
public final class PostgresTestSupport {

    public static final String DB_NAME = "sql_audit_it";
    public static final String DB_USER = "postgres";
    public static final String DB_PASSWORD = "postgres";

    /** 独立测试库连接串（connectTimeout 保证无库时快速失败以便 Assumptions 跳过）。 */
    public static final String JDBC_URL =
            "jdbc:postgresql://127.0.0.1:5432/" + DB_NAME + "?connectTimeout=3&socketTimeout=30";

    /** 不指定库的服务器级连接（建库 / 探活用；PG 必须连某个库，用系统库 postgres）。 */
    private static final String SERVER_URL =
            "jdbc:postgresql://127.0.0.1:5432/postgres?connectTimeout=3&socketTimeout=30";

    private static final String MIGRATION_LOCATION = "classpath:db/migration/postgresql";
    private static final String SEED_DIALECT_DIR = "postgresql";

    private PostgresTestSupport() {
    }

    /** 探测本地 PostgreSQL 是否可用（供 Assumptions.assumeTrue 优雅跳过）。 */
    public static boolean postgresAvailable() {
        try (Connection ignored = DriverManager.getConnection(SERVER_URL, DB_USER, DB_PASSWORD)) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /** 独立测试库不存在则创建（查 pg_database 后补建；PG 标识符小写）。 */
    public static void ensureDatabase() throws SQLException {
        try (Connection conn = DriverManager.getConnection(SERVER_URL, DB_USER, DB_PASSWORD);
             Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = '" + DB_NAME + "'")) {
                if (rs.next()) {
                    return;
                }
            }
            st.execute("CREATE DATABASE " + DB_NAME);
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
     * 逐张 TRUNCATE（RESTART IDENTITY 复位自增序列），再重放 {@code V2__seed.sql}
     * （创世块 / 角色 / 权限 / 授权 / 账号 / 规则 / 审批流全部回到种子状态）+ V3 及以后迁移文件的 DML（新端点的权限绑定行等），
     * 使测试方法之间数据独立且起点一致。
     */
    public static void cleanAndSeed(JdbcTemplate jdbc) {
        List<String> tables = businessTables(jdbc);
        for (String table : tables) {
            jdbc.execute("TRUNCATE TABLE \"" + table + "\" RESTART IDENTITY");
        }
        for (String sql : SeedSql.replayStatements(SEED_DIALECT_DIR)) {
            jdbc.execute(sql);
        }
    }

    /** 从库元数据读取 public schema 下的全部基表名（排除 Flyway 历史表），按名字排序保证稳定。 */
    public static List<String> businessTables(JdbcTemplate jdbc) {
        // PG 的表清单走 schema（= public），catalog 为空
        return jdbc.execute((Connection conn) -> {
            List<String> names = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData()
                    .getTables(null, "public", "%", new String[]{"TABLE"})) {
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
