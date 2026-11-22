package com.sqlaudit.storage.impl.jdbc.h2;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC DAO 集成测试基建（H2 版）：建连接池、跑 Flyway V1 DDL、每个测试前清空全部表。
 *
 * <p><strong>目标库：</strong>H2 内存库（{@code MODE=PostgreSQL}，无 Docker 依赖的测试方言），
 * {@code jdbc:h2:mem:sqlaudit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}。
 * {@code DB_CLOSE_DELAY=-1} 让内存库在连接池存续期间不销毁（进程级）。</p>
 *
 * <p>与 {@code PostgresTestSupport} 的差异：H2 是嵌入式库，无需 {@code postgresAvailable()}
 * 探测与 Assumptions 跳过；无物理外键故无需 {@code SET FOREIGN_KEY_CHECKS}，直接 TRUNCATE 即可。</p>
 */
public final class H2TestSupport {

    /** H2 内存库（MODE=PostgreSQL）。DATABASE_TO_LOWER 对齐未加引号标识符小写语义。 */
    public static final String JDBC_URL =
            "jdbc:h2:mem:sqlaudit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    public static final String DB_USER = "sa";
    public static final String DB_PASSWORD = "";

    /** V1__init.sql 的全部 23 张表（cleanAllTables 逐张 TRUNCATE）。 */
    public static final List<String> ALL_TABLES = List.of(
            "t_user", "t_role", "t_user_role", "t_instance", "t_credential",
            "t_rule", "t_rule_group", "t_rule_group_binding", "t_flow_rule", "t_flow_node",
            "t_ticket", "t_ticket_unit", "t_audit_result", "t_rollback_sql", "t_rollback_statement",
            "t_approval", "t_exec_lock", "t_chain_head", "t_audit_log",
            "t_query_log", "t_masking_rule", "t_export_task", "t_worker_heartbeat");

    /** hash 链创世块：last_hash 与 §7.3 定义一致（64 个 '0'）。 */
    private static final String GENESIS_HASH = "0".repeat(64);

    private H2TestSupport() {
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
     * 幂等建表：先 DROP 全部旧表，再执行 classpath 的 {@code db/migration/h2/V1__init.sql}
     * （按 {@code ;\n} 拆分逐条执行——DDL 内无行中分号，CHECK 约束等多行语句拆分安全）。
     */
    public static void ensureSchema(HikariDataSource ds) throws Exception {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            for (String table : ALL_TABLES) {
                st.execute("DROP TABLE IF EXISTS " + table);
            }
            String ddl = readV1Ddl();
            for (String sql : splitStatements(ddl)) {
                st.execute(sql);
            }
        }
    }

    /**
     * 每个测试前清库：TRUNCATE 全部 23 张表（H2 无物理外键，直接 TRUNCATE 即可），
     * 保证测试类之间 / 测试方法之间数据独立；随后重放 DDL 末尾的初始化数据
     * （chain_head 创世块 + 内置角色），使链头恒为 genesis 状态。
     */
    public static void cleanAllTables(JdbcTemplate jdbc) {
        jdbc.execute((ConnectionCallback<Void>) conn -> {
            try (Statement st = conn.createStatement()) {
                for (String table : ALL_TABLES) {
                    st.execute("TRUNCATE TABLE " + table);
                }
            }
            return null;
        });
        // 初始化数据（与 V1 末尾一致）：TRUNCATE 会清掉 chain_head 创世块，必须重放
        jdbc.update("INSERT INTO t_chain_head (id, last_seq, last_hash) VALUES (1, 0, ?)", GENESIS_HASH);
        jdbc.update("INSERT INTO t_role (code, name, description) VALUES "
                + "('DEV', '开发', '提交工单、查询通道'), "
                + "('LEAD', '主管', '审批（非本人）'), "
                + "('DBA', 'DBA', '执行/二次确认、规则与流程配置'), "
                + "('ADMIN', '管理员', '凭据管理、审计检索导出、全量配置')");
    }

    // ---- 内部工具 ----

    private static String readV1Ddl() throws Exception {
        try (InputStream in = H2TestSupport.class
                .getClassLoader().getResourceAsStream("db/migration/h2/V1__init.sql")) {
            if (in == null) {
                throw new IllegalStateException("classpath 下未找到 db/migration/h2/V1__init.sql");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 按 {@code ;换行} 拆分多语句 DDL；空块（文件尾）跳过。 */
    static List<String> splitStatements(String ddl) {
        List<String> statements = new ArrayList<>();
        for (String chunk : ddl.split(";\\r?\\n")) {
            String sql = chunk.strip();
            if (!sql.isEmpty()) {
                statements.add(sql);
            }
        }
        return statements;
    }
}
