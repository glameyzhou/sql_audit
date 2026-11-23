package com.sqlaudit.server.service;

import com.sqlaudit.common.crypto.CredentialCrypto;
import com.sqlaudit.common.crypto.CredentialCryptoException;
import com.sqlaudit.common.enums.AccountType;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.storage.dao.CredentialDao;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.model.Credential;
import com.sqlaudit.storage.model.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 实例连通性测试与库表元数据浏览（JDBC 直连，按请求现建现关，不落连接池）。
 *
 * <p>两类用途：</p>
 * <ul>
 *   <li>{@link #testConnection}：用调用方提交的裸连接参数（方言 / host / port / 账号 /
 *       密码 / 库）经 {@link DriverManager} 试连，结果一律以 {@code {ok, message}} 返回，
 *       <strong>绝不抛异常</strong>（IoTDB 直接 ok:false 提示暂不支持，连接超时 ≤5s）；</li>
 *   <li>{@link #listDatabases} / {@link #listTables}：按实例 id 走 {@code RO_META}
 *       只读凭据（{@link CredentialDao#findByInstanceAndType} + {@link CredentialCrypto#decrypt}）
 *       连生产库取库表清单，与 RW_EXEC 执行账号物理隔离。实例不存在 / 无 RO_META 凭据 /
 *       IoTDB 方言以 {@link SqlauditException}（HTTP 400）报出。</li>
 * </ul>
 *
 * <p>标识符拼接（MySQL 反引号 / PG 双引号）一律先经 {@link #mysqlQuote} / {@link #pgQuote}
 * 转义，库名表名均可能来自前端输入，防呆不可省。</p>
 */
@Service
public class JdbcMetadataService {

    private static final Logger log = LoggerFactory.getLogger(JdbcMetadataService.class);

    /** 连接登录超时（秒），覆盖「连接超时 ≤5s」要求（PG/MySQL 驱动均支持 loginTimeout）。 */
    private static final int LOGIN_TIMEOUT_SECONDS = 5;

    /** MySQL 系统库（SHOW DATABASES 结果过滤）。 */
    private static final List<String> MYSQL_SYSTEM_SCHEMAS =
            List.of("information_schema", "mysql", "performance_schema", "sys");

    /** PG 无默认库时的引导库（PG 建连必须有库名，postgres 库恒存在）。 */
    private static final String PG_BOOTSTRAP_DB = "postgres";

    private final InstanceDao instanceDao;
    private final CredentialDao credentialDao;

    public JdbcMetadataService(InstanceDao instanceDao, CredentialDao credentialDao) {
        this.instanceDao = instanceDao;
        this.credentialDao = credentialDao;
    }

    // ------------------------------------------------------------------
    // 连通性测试（绝不抛异常）
    // ------------------------------------------------------------------

    /**
     * 用裸连接参数试连生产库。
     *
     * <p>成功返回 {@code {ok:true, message:"连接成功"}}；一切失败（参数非法 / 方言未知 /
     * 网络不可达 / 鉴权失败）都归到 {@code {ok:false, message:简短原因}}，IoTDB 直接
     * ok:false 提示暂不支持。方法签名保证不抛任何异常（含 Error 级别防御）。</p>
     */
    public Map<String, Object> testConnection(String dialect, String host, Integer port,
                                              String username, String password, String database) {
        Dialect d = parseDialect(dialect);
        if (d == null) {
            return fail("不支持的数据库类型: " + dialect);
        }
        if (d == Dialect.IOTDB) {
            return fail("IoTDB 暂不支持连接测试");
        }
        if (host == null || host.isBlank()) {
            return fail("host 不能为空");
        }
        int p = port == null ? defaultPort(d) : port;
        try {
            DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
            try (Connection conn = DriverManager.getConnection(
                    jdbcUrl(d, host, p, database), username, password)) {
                // 拿到连接即视为连通，不校验具体库权限
            }
            return Map.of("ok", true, "message", "连接成功");
        } catch (Throwable t) {
            log.warn("实例连通性测试失败 dialect={} host={} port={}: {}", dialect, host, p, t.getMessage());
            return fail(shortMessage(t));
        }
    }

    // ------------------------------------------------------------------
    // 库表元数据浏览（RO_META 凭据）
    // ------------------------------------------------------------------

    /**
     * 经实例 RO_META 凭据列生产库的库清单。
     *
     * <ul>
     *   <li>MySQL：{@code SHOW DATABASES}，过滤 information_schema / mysql /
     *       performance_schema / sys；</li>
     *   <li>PG：{@code SELECT datname FROM pg_database WHERE datistemplate=false ORDER BY datname}；</li>
     *   <li>IoTDB / 实例不存在 / 无 RO_META 凭据 → {@link SqlauditException}（400）。</li>
     * </ul>
     */
    public List<String> listDatabases(long instanceId) {
        Resolved r = resolveMeta(instanceId);
        String sql = r.dialect() == Dialect.PG
                ? "SELECT datname FROM pg_database WHERE datistemplate=false ORDER BY datname"
                : "SHOW DATABASES";
        try (Connection conn = open(r, bootstrapDatabase(r));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                String name = rs.getString(1);
                if (r.dialect() == Dialect.MYSQL
                        && MYSQL_SYSTEM_SCHEMAS.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                out.add(name);
            }
            return out;
        } catch (SQLException e) {
            throw new SqlauditException(ErrorCode.META_QUERY_FAILED, "库列表查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * 经实例 RO_META 凭据列指定库的表清单。
     *
     * <ul>
     *   <li>MySQL：{@code SHOW FULL TABLES FROM `库名`}，取第一列（表名）；</li>
     *   <li>PG：<strong>database 参数即数据库名</strong>——连接切到该库，列出所有用户 schema
     *       下的表，返回 {@code schema.table} 限定名（排除 pg_catalog / information_schema），
     *       与前端「点库 → 看表 → 点表查询」的两级浏览语义对齐；</li>
     *   <li>IoTDB / 实例不存在 / 无 RO_META 凭据 → {@link SqlauditException}（400）。</li>
     * </ul>
     */
    public List<String> listTables(long instanceId, String database) {
        if (database == null || database.isBlank()) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "库名不能为空");
        }
        Resolved r = resolveMeta(instanceId);
        if (r.dialect() == Dialect.MYSQL) {
            String sql = "SHOW FULL TABLES FROM " + mysqlQuote(database);
            try (Connection conn = open(r, bootstrapDatabase(r));
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            } catch (SQLException e) {
                throw new SqlauditException(ErrorCode.META_QUERY_FAILED, "表列表查询失败: " + e.getMessage(), e);
            }
        }
        // PG：连接到所选数据库（database 参数），跨 schema 返回限定名
        String sql = "SELECT schemaname || '.' || tablename FROM pg_tables"
                + " WHERE schemaname NOT IN ('pg_catalog', 'information_schema')"
                + " ORDER BY schemaname, tablename";
        try (Connection conn = open(r, database);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new SqlauditException(ErrorCode.META_QUERY_FAILED,
                    "表列表查询失败（请确认库 " + database + " 存在且 RO_META 可访问）: "
                            + e.getMessage(), e);
        }
    }

    /**
     * 经实例 RO_META 凭据列指定表的列名清单（按定义顺序）。
     *
     * <ul>
     *   <li>MySQL：查 {@code information_schema.COLUMNS}，按 {@code TABLE_SCHEMA=库} +
     *       {@code TABLE_NAME=表} 过滤；</li>
     *   <li>PG：{@code table} 为 {@code schema.table} 限定名（与 {@link #listTables} 返回
     *       口径一致），拆成 schema/table 过滤；裸表名回落 {@code public}；</li>
     *   <li>IoTDB / 实例不存在 / 无 RO_META 凭据 → {@link SqlauditException}（400）。</li>
     * </ul>
     *
     * <p>库名/表名一律走 {@link PreparedStatement} 参数绑定，不拼接进 SQL，防注入。</p>
     */
    public List<String> listColumns(long instanceId, String database, String table) {
        if (database == null || database.isBlank()) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "库名不能为空");
        }
        if (table == null || table.isBlank()) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "表名不能为空");
        }
        Resolved r = resolveMeta(instanceId);
        if (r.dialect() == Dialect.MYSQL) {
            String sql = "SELECT COLUMN_NAME FROM information_schema.COLUMNS"
                    + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?"
                    + " ORDER BY ORDINAL_POSITION";
            return queryColumns(r, bootstrapDatabase(r), sql, database, table, table);
        }
        // PG：table 为 schema.table 限定名（listTables 口径），拆开过滤；裸表名回落 public
        String schema = "public";
        String tbl = table;
        int dot = table.indexOf('.');
        if (dot > 0) {
            schema = table.substring(0, dot);
            tbl = table.substring(dot + 1);
        }
        String sql = "SELECT column_name FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = ?"
                + " ORDER BY ordinal_position";
        return queryColumns(r, database, sql, schema, tbl, table);
    }

    /** 列名查询公共体：参数绑定执行，统一转 {@link SqlauditException}（400）。 */
    private List<String> queryColumns(Resolved r, String connectDb, String sql,
                                      String p1, String p2, String label) {
        try (Connection conn = open(r, connectDb);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, p1);
            ps.setString(2, p2);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new SqlauditException(ErrorCode.META_QUERY_FAILED,
                    "列列表查询失败（表 " + label + "）: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 实例路由与凭据
    // ------------------------------------------------------------------

    /** 解析实例 + RO_META 凭据（{@link AccountType#RO_META}）+ 明文密码；任何前置条件缺失抛 {@link SqlauditException}（400）。 */
    private Resolved resolveMeta(long instanceId) {
        Instance inst = instanceDao.findById(instanceId);
        if (inst == null) {
            throw new SqlauditException(ErrorCode.INSTANCE_NOT_FOUND, "实例不存在: id=" + instanceId);
        }
        Dialect dialect = dialectOf(inst);
        if (dialect == Dialect.IOTDB) {
            throw new SqlauditException(ErrorCode.IOTDB_NOT_SUPPORTED, "IoTDB 实例暂不支持库表浏览");
        }
        Credential cred = credentialDao.findByInstanceAndType(instanceId, AccountType.RO_META);
        if (cred == null) {
            throw new SqlauditException(ErrorCode.RO_META_CREDENTIAL_MISSING,
                    "实例缺少 RO_META 凭据: id=" + instanceId);
        }
        String password;
        try {
            password = CredentialCrypto.decrypt(cred.getPasswordEnc());
        } catch (CredentialCryptoException e) {
            throw new SqlauditException(ErrorCode.RO_META_CREDENTIAL_INVALID,
                    "RO_META 凭据解密失败: " + e.getMessage(), e);
        }
        return new Resolved(inst, dialect, cred, password);
    }

    private record Resolved(Instance instance, Dialect dialect, Credential credential, String password) {
    }

    // ------------------------------------------------------------------
    // JDBC 连接
    // ------------------------------------------------------------------

    /** 按解析结果开 JDBC 连接（DriverManager，loginTimeout ≤5s；调用方负责关闭）。 */
    private static Connection open(Resolved r, String database) throws SQLException {
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        return DriverManager.getConnection(
                jdbcUrl(r.dialect(), r.instance().getHost(), portOf(r), database),
                r.credential().getUsername(), r.password());
    }

    /** 建连用库名：MySQL 用实例默认库（可为空）；PG 用默认库，空则回落 postgres 引导库。 */
    private static String bootstrapDatabase(Resolved r) {
        String schema = r.instance().getDefaultSchema();
        if (r.dialect() == Dialect.PG && (schema == null || schema.isBlank())) {
            return PG_BOOTSTRAP_DB;
        }
        return schema == null ? "" : schema;
    }

    private static int portOf(Resolved r) {
        Integer port = r.instance().getPort();
        return port == null ? defaultPort(r.dialect()) : port;
    }

    private static int defaultPort(Dialect dialect) {
        return dialect == Dialect.PG ? 5432 : 3306;
    }

    /** 拼 JDBC URL（库名可为空；超时参数按方言单位：MySQL 毫秒、PG 秒）。 */
    private static String jdbcUrl(Dialect dialect, String host, int port, String database) {
        String db = database == null || database.isBlank() ? "" : database;
        if (dialect == Dialect.PG) {
            return "jdbc:postgresql://" + host + ":" + port + "/" + db
                    + "?connectTimeout=" + LOGIN_TIMEOUT_SECONDS + "&socketTimeout=30";
        }
        return "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&connectTimeout=" + (LOGIN_TIMEOUT_SECONDS * 1000) + "&socketTimeout=30000";
    }

    // ------------------------------------------------------------------
    // 方言与标识符转义（防呆）
    // ------------------------------------------------------------------

    /** 方言解析：兼容 {@code MYSQL} / {@code PG} / {@code POSTGRESQL} / {@code IOTDB}，未知返回 null。 */
    private static Dialect parseDialect(String dialect) {
        if (dialect == null) {
            return null;
        }
        return switch (dialect.trim().toUpperCase(Locale.ROOT)) {
            case "MYSQL" -> Dialect.MYSQL;
            case "PG", "POSTGRESQL", "POSTGRES" -> Dialect.PG;
            case "IOTDB" -> Dialect.IOTDB;
            default -> null;
        };
    }

    /** 实例方言（与 server 端 {@code JdbcProductionGateway} 同口径：列值已由存储层按存储码解码为枚举，为空回落 MYSQL）。 */
    private static Dialect dialectOf(Instance inst) {
        return inst.getDialect() == null ? Dialect.MYSQL : inst.getDialect();
    }

    /** MySQL 标识符转义：反引号内的反引号翻倍后整体包反引号（拼接前必调）。 */
    static String mysqlQuote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    /** PG 标识符转义：双引号内的双引号翻倍后整体包双引号（拼接前必调）。 */
    static String pgQuote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    // ------------------------------------------------------------------
    // 消息工具
    // ------------------------------------------------------------------

    private static Map<String, Object> fail(String message) {
        return Map.of("ok", false, "message", message);
    }

    /** 取异常一句话简短原因：去换行、截断（前端只展示一句提示）。 */
    private static String shortMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getMessage() == null && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = cause.getClass().getSimpleName();
        }
        msg = msg.replace('\n', ' ').replace('\r', ' ').trim();
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }
}
