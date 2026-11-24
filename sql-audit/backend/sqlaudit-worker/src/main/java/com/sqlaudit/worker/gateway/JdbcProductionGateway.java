package com.sqlaudit.worker.gateway;

import com.sqlaudit.common.crypto.CredentialCrypto;
import com.sqlaudit.common.crypto.CredentialCryptoException;
import com.sqlaudit.common.enums.AccountType;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.IotdbSqlDialect;
import com.sqlaudit.executor.gateway.ProductionGateway;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.model.Instance;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生产库真执行网关（W2）：按实例路由建 JDBC 连接，单事务内完成
 * 前镜像 → DML → 对账 → COMMIT/ROLLBACK，协议语义严格对齐 {@link ProductionGateway}。
 *
 * <p>实现要点（README §4.4）：</p>
 * <ul>
 *   <li>同一 {@link Connection}、同一事务：前镜像 SELECT FOR UPDATE 与 DML 同事务执行；</li>
 *   <li>隔离级别：MySQL 保持默认 RR 且禁止降级 RC；PG 显式提升到 REPEATABLE READ；</li>
 *   <li>对账先行：COMMIT 前比对 affectedRows == preimageRows.size()，不等则回滚 + RECONCILE_FAILED；</li>
 *   <li>INSERT 无前镜像不做对账，按方言取主键（MySQL RETURN_GENERATED_KEYS / PG RETURNING）。</li>
 * </ul>
 *
 * <p>连接池按实例 id 缓存（惰性创建，实例变更时最简单策略是重建 JVM 内缓存，
 * 保持简单）。凭据经平台库 {@code JdbcTemplate} 直查 {@code t_credential}，密文用
 * {@code CredentialCrypto}（AES-GCM）解密后建连接。</p>
 */
public class JdbcProductionGateway implements ProductionGateway {

    private final InstanceDao instanceDao;
    private final JdbcTemplate platformJdbc;

    /** 按实例 id 缓存的连接池，避免每次执行重建连接。 */
    private final ConcurrentHashMap<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    public JdbcProductionGateway(InstanceDao instanceDao, JdbcTemplate platformJdbc) {
        this.instanceDao = instanceDao;
        this.platformJdbc = platformJdbc;
    }

    @Override
    public ExecutionResult executeInTransaction(ExecutionRequest request) {
        // 1. 实例路由（README §4.4）：查不到实例不连生产库
        Instance inst = instanceDao.findById(request.instanceId());
        if (inst == null) {
            return ExecutionResult.executionError("实例不存在: id=" + request.instanceId());
        }
        Dialect dialect = dialectOf(inst);
        if (dialect == Dialect.IOTDB) {
            return executeIotdb(inst, request);
        }

        // 2. RW_EXEC 凭据（平台库 t_credential，AES-GCM 解密）
        Credential cred;
        try {
            cred = loadCredential(request.instanceId());
        } catch (CredentialCryptoException e) {
            return ExecutionResult.executionError("凭据解密失败: " + e.getMessage());
        }
        if (cred == null) {
            return ExecutionResult.executionError("实例缺少 RW_EXEC 凭据: id=" + request.instanceId());
        }

        HikariDataSource ds = poolFor(inst, cred);
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 3. 隔离级别断言（README §4.4：云厂商/中间件可能改默认值）
                assertIsolation(conn, dialect);

                List<Map<String, Object>> preimage = null;
                long affected;
                List<Object> insertedPk = null;

                if (request.preimageSql() != null) {
                    // 4. 前镜像（SELECT ... FOR UPDATE，与 DML 同事务锁行）
                    preimage = readPreimage(conn, request.preimageSql());
                    // 5. DML（matched rows 口径）
                    affected = executeUpdate(conn, request.statementSql());
                } else {
                    // INSERT：无前镜像，一次执行并取主键（README §4.4）
                    InsertResult r = executeInsert(conn, request.statementSql(),
                            request.pkColumns(), dialect);
                    affected = r.affectedRows();
                    insertedPk = r.pkValues();
                }

                // 6. 对账先行（README §4.4 硬门禁）：affected != preimage.size() → 回滚
                if (preimage != null && affected != preimage.size()) {
                    conn.rollback();
                    return ExecutionResult.reconcileFailure(preimage.size(), affected);
                }

                conn.commit();
                return ExecutionResult.success(preimage, affected, insertedPk);
            } catch (Exception e) {
                conn.rollback();
                return ExecutionResult.executionError(String.valueOf(e.getMessage()));
            }
        } catch (SQLException e) {
            return ExecutionResult.executionError("连接失败: " + e.getMessage());
        }
    }

    /**
     * IoTDB 执行（README §4.4）：无事务、无前镜像、无对账——IoTDB 驱动无行级反向操作、
     * affected 恒 0、rollback 无效（写入即时生效），直接执行语句，恢复靠元数据快照 + SOP。
     * 前镜像为 null、affectedRows 记 0（对账在引擎层本就不会对 IoTDB 触发）。
     */
    private ExecutionResult executeIotdb(Instance inst, ExecutionRequest request) {
        Credential cred;
        try {
            cred = loadCredential(request.instanceId());
        } catch (CredentialCryptoException e) {
            return ExecutionResult.executionError("凭据解密失败: " + e.getMessage());
        }
        if (cred == null) {
            return ExecutionResult.executionError("实例缺少 RW_EXEC 凭据: id=" + request.instanceId());
        }
        ensureIotdbDriverLoaded();
        DriverManager.setLoginTimeout(5);
        try (Connection conn = DriverManager.getConnection(
                jdbcUrl(inst, Dialect.IOTDB), cred.username(), cred.password());
             Statement st = conn.createStatement()) {
            st.execute(request.statementSql());
            return ExecutionResult.success(null, 0, null);
        } catch (SQLException e) {
            return ExecutionResult.executionError("IoTDB 执行失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 事务内步骤
    // ------------------------------------------------------------------

    /** 前镜像逐行读为 Map&lt;列 label, 值&gt;（列序与 PreimagePlanner 对齐）。 */
    private static List<Map<String, Object>> readPreimage(Connection conn, String preimageSql)
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(preimageSql);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** UPDATE/DELETE 执行（affected = matched rows 口径，MySQL useAffectedRows=false）。 */
    private static long executeUpdate(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            return ps.executeUpdate();
        }
    }

    /** INSERT 执行 + 主键取回（分方言，README §4.4）。 */
    private static InsertResult executeInsert(Connection conn, String sql,
                                              List<String> pkColumns, Dialect dialect)
            throws SQLException {
        if (dialect == Dialect.PG) {
            // PG：改写加 RETURNING <pk>（参照 Postgres*Dao 的做法）
            if (pkColumns == null || pkColumns.isEmpty()) {
                // 无主键元数据无法取回（W2 由 GatewayBridge 透传 pk 列名）
                long affected = executeUpdate(conn, sql);
                return new InsertResult(affected, List.of());
            }
            String rewritten = sql.trim();
            if (rewritten.endsWith(";")) {
                rewritten = rewritten.substring(0, rewritten.length() - 1);
            }
            rewritten = rewritten + " RETURNING " + String.join(", ", pkColumns);
            List<Object> pk = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(rewritten);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    for (int i = 1; i <= pkColumns.size(); i++) {
                        pk.add(rs.getObject(i));
                    }
                }
            }
            // INSERT 无对账，affected 取取回主键行数（多行 INSERT 时即插入行数）
            return new InsertResult(pk.size(), pk);
        }

        // MySQL：Statement.RETURN_GENERATED_KEYS（auto_increment 主键；或 LAST_INSERT_ID()）
        List<Object> pk = new ArrayList<>();
        long affected;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            affected = ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                while (rs.next()) {
                    pk.add(rs.getObject(1));
                }
            }
        }
        return new InsertResult(affected, pk);
    }

    // ------------------------------------------------------------------
    // 连接与隔离级别
    // ------------------------------------------------------------------

    /** 按实例缓存的连接池（惰性创建）。 */
    private HikariDataSource poolFor(Instance inst, Credential cred) {
        return pools.computeIfAbsent(inst.getId(), id -> {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(jdbcUrl(inst, dialectOf(inst)));
            cfg.setUsername(cred.username());
            cfg.setPassword(cred.password());
            cfg.setMaximumPoolSize(4);
            cfg.setConnectionTimeout(5000);
            return new HikariDataSource(cfg);
        });
    }

    /**
     * 隔离级别断言（README §4.4）。
     * PG 默认 READ COMMITTED，须显式提升到 REPEATABLE READ；MySQL 保持默认 RR 且禁止降级 RC。
     */
    private static void assertIsolation(Connection conn, Dialect dialect) throws SQLException {
        if (dialect == Dialect.PG) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            }
        }
        int level = conn.getTransactionIsolation();
        if (dialect == Dialect.MYSQL
                && level != Connection.TRANSACTION_REPEATABLE_READ
                && level != Connection.TRANSACTION_SERIALIZABLE) {
            throw new SQLException("MySQL 会话隔离级别异常（禁止降级 RC/RU）: " + level);
        }
        if (dialect == Dialect.PG) {
            // getTransactionIsolation 可能不反映 SET TRANSACTION（驱动侧缓存），以权威 SHOW 校验
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SHOW transaction_isolation")) {
                if (!rs.next() || !"repeatable read".equalsIgnoreCase(rs.getString(1))) {
                    throw new SQLException("PG 未处于 REPEATABLE READ: "
                            + (rs.wasNull() ? "unknown" : rs.getString(1)));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private Credential loadCredential(long instanceId) {
        List<Credential> creds = platformJdbc.query(
                "SELECT username, password_enc FROM t_credential "
                        + "WHERE instance_id=? AND account_type=? LIMIT 1",
                (rs, i) -> {
                    String username = rs.getString("username");
                    byte[] enc = rs.getBytes("password_enc");
                    String encrypted = enc == null ? null : new String(enc, StandardCharsets.UTF_8);
                    String password = encrypted == null ? null : CredentialCrypto.decrypt(encrypted);
                    return new Credential(username, password);
                }, instanceId, AccountType.RW_EXEC.getCode());
        return creds.isEmpty() ? null : creds.get(0);
    }

    private static Dialect dialectOf(Instance inst) {
        // 实例方言写入时已校验为 Dialect 枚举，未知取值到不了这里；仅保留 null 兜底
        return inst.getDialect() == null ? Dialect.MYSQL : inst.getDialect();
    }

    private static String jdbcUrl(Instance inst, Dialect dialect) {
        String host = inst.getHost();
        int port = inst.getPort() == null ? defaultPort(dialect) : inst.getPort();
        String schema = inst.getDefaultSchema() == null ? "" : inst.getDefaultSchema();
        if (dialect == Dialect.PG) {
            return "jdbc:postgresql://" + host + ":" + port + "/" + schema
                    + "?connectTimeout=3&socketTimeout=30";
        }
        if (dialect == Dialect.IOTDB) {
            // 树/表双模型是连接级方言开关（默认 tree），表模型（宽表）须 ?sql_dialect=table
            return inst.getIotdbSqlDialect() == IotdbSqlDialect.TABLE
                    ? "jdbc:iotdb://" + host + ":" + port + "?sql_dialect=table"
                    : "jdbc:iotdb://" + host + ":" + port + "/";
        }
        // MySQL：useAffectedRows 保持 false（默认）——getUpdateCount 返回 matched rows 才能对账（README §4.4）
        return "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=false"
                + "&connectTimeout=3000&socketTimeout=30000";
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

    /** IoTDB 2.x JDBC 驱动无 SPI 注册，Class.forName 触发其 static 块向 DriverManager 注册。 */
    private static void ensureIotdbDriverLoaded() {
        try {
            Class.forName("org.apache.iotdb.jdbc.IoTDBDriver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("IoTDB JDBC 驱动未加载: org.apache.iotdb.jdbc.IoTDBDriver", e);
        }
    }

    private record Credential(String username, String password) {
    }

    private record InsertResult(long affectedRows, List<Object> pkValues) {
    }
}
