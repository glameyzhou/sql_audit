package com.sqlaudit.server.service;

import com.sqlaudit.common.crypto.CredentialCrypto;
import com.sqlaudit.common.crypto.CredentialCryptoException;
import com.sqlaudit.common.enums.AccountType;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.enums.IotdbSqlDialect;
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
 * <p>库名 / 表名一律走 {@link PreparedStatement} 参数绑定（不拼接进 SQL），
 * 它们均可能来自前端输入，防注入不可省。</p>
 */
@Service
public class JdbcMetadataService {

    private static final Logger log = LoggerFactory.getLogger(JdbcMetadataService.class);

    /** 连接登录超时（秒），覆盖「连接超时 ≤5s」要求（PG/MySQL 驱动均支持 loginTimeout）。 */
    private static final int LOGIN_TIMEOUT_SECONDS = 5;

    /** IoTDB 2.x JDBC 驱动类（jar 无 SPI 注册，须 Class.forName 显式加载后 DriverManager 才能识别）。 */
    private static final String IOTDB_DRIVER_CLASS = "org.apache.iotdb.jdbc.IoTDBDriver";

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
        if (host == null || host.isBlank()) {
            return fail("host 不能为空");
        }
        int p = port == null ? defaultPort(d) : port;
        try {
            ensureDriverLoaded(d);
            DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
            try (Connection conn = DriverManager.getConnection(
                    jdbcUrl(d, host, p, database, null), username, password)) {
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
        if (r.dialect() == Dialect.IOTDB) {
            return listIotdbDatabases(r);
        }
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
     * 经实例 RO_META 凭据列指定库的表清单（含原生注释）。
     *
     * <ul>
     *   <li>MySQL：查 {@code information_schema.TABLES}（BASE TABLE + VIEW），
     *       {@code TABLE_COMMENT} 为原生注释；</li>
     *   <li>PG：<strong>database 参数即数据库名</strong>——连接切到该库，列出所有用户 schema
     *       下的表，返回 {@code schema.table} 限定名（排除 pg_catalog / information_schema），
     *       注释经 {@code obj_description(c.oid, 'pg_class')} 取（无注释为 null）；
     *       与前端「点库 → 看表 → 点表查询」的两级浏览语义对齐；</li>
     *   <li>IoTDB / 实例不存在 / 无 RO_META 凭据 → {@link SqlauditException}（400）。</li>
     * </ul>
     *
     * <p>展示时平台侧备注（t_table_remark）优先于本方法返回的原生注释，合并逻辑在
     * 前端（备注经 {@code GET /api/table-remarks} 单独下发）。</p>
     */
    public List<TableRef> listTables(long instanceId, String database) {
        if (database == null || database.isBlank()) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "库名不能为空");
        }
        Resolved r = resolveMeta(instanceId);
        if (r.dialect() == Dialect.IOTDB) {
            return listIotdbTables(r, database);
        }
        if (r.dialect() == Dialect.MYSQL) {
            String sql = "SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES"
                    + " WHERE TABLE_SCHEMA = ? AND TABLE_TYPE IN ('BASE TABLE', 'VIEW')"
                    + " ORDER BY TABLE_NAME";
            try (Connection conn = open(r, bootstrapDatabase(r));
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, database);
                try (ResultSet rs = ps.executeQuery()) {
                    List<TableRef> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new TableRef(rs.getString(1), blankToNull(rs.getString(2))));
                    }
                    return out;
                }
            } catch (SQLException e) {
                throw new SqlauditException(ErrorCode.META_QUERY_FAILED, "表列表查询失败: " + e.getMessage(), e);
            }
        }
        // PG：连接到所选数据库（database 参数），跨 schema 返回限定名；注释走 pg_class
        String sql = "SELECT pt.schemaname || '.' || pt.tablename, obj_description(c.oid, 'pg_class')"
                + " FROM pg_tables pt"
                + " JOIN pg_class c ON c.relname = pt.tablename"
                + " JOIN pg_namespace n ON n.nspname = pt.schemaname AND n.oid = c.relnamespace"
                + " WHERE pt.schemaname NOT IN ('pg_catalog', 'information_schema')"
                + " ORDER BY pt.schemaname, pt.tablename";
        try (Connection conn = open(r, database);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            List<TableRef> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new TableRef(rs.getString(1), blankToNull(rs.getString(2))));
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
        if (r.dialect() == Dialect.IOTDB) {
            return listIotdbColumns(r, database, table);
        }
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

    /**
     * 经实例 RO_META 凭据列指定表的表结构（字段 / 类型 / 可空 / 键 / 默认值 / 注释）。
     *
     * <ul>
     *   <li>MySQL：查 {@code information_schema.COLUMNS}（{@code COLUMN_TYPE} 为完整类型，
     *       {@code COLUMN_KEY} 为键：PRI 主键 / UNI 唯一 / MUL 普通索引 / 空串无键）；</li>
     *   <li>PG：{@code table} 为 {@code schema.table} 限定名（与 {@link #listTables} 返回
     *       口径一致），拆成 schema/table 过滤；裸表名回落 {@code public}；注释经
     *       {@code col_description(pg_class.oid, ordinal_position)} 取；键经
     *       key_column_usage + table_constraints（PRIMARY KEY → PRI / UNIQUE → UNI）；</li>
     *   <li>IoTDB → {@link SqlauditException}（400，IoTDB 无 information_schema 等价物，
     *       「IoTDB 暂不支持表结构查看」）；实例不存在 / 无 RO_META 凭据 → 400。</li>
     * </ul>
     *
     * <p>库名/表名一律走 {@link PreparedStatement} 参数绑定，不拼接进 SQL，防注入。</p>
     */
    public List<TableColumn> tableStructure(long instanceId, String database, String table) {
        if (database == null || database.isBlank()) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "库名不能为空");
        }
        if (table == null || table.isBlank()) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "表名不能为空");
        }
        Resolved r = resolveMeta(instanceId);
        if (r.dialect() == Dialect.IOTDB) {
            return iotdbTableStructure(r, database, table);
        }
        if (r.dialect() == Dialect.MYSQL) {
            String sql = "SELECT ORDINAL_POSITION, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE,"
                    + " COLUMN_KEY, COLUMN_DEFAULT, COLUMN_COMMENT"
                    + " FROM information_schema.COLUMNS"
                    + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?"
                    + " ORDER BY ORDINAL_POSITION";
            try (Connection conn = open(r, bootstrapDatabase(r));
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, database);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    List<TableColumn> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new TableColumn(rs.getInt(1), rs.getString(2), rs.getString(3),
                                "YES".equalsIgnoreCase(rs.getString(4)), blankToNull(rs.getString(5)),
                                rs.getString(6), blankToNull(rs.getString(7))));
                    }
                    return out;
                }
            } catch (SQLException e) {
                throw new SqlauditException(ErrorCode.META_QUERY_FAILED,
                        "表结构查询失败（表 " + table + "）: " + e.getMessage(), e);
            }
        }
        // PG：table 为 schema.table 限定名（listTables 口径），拆开过滤；裸表名回落 public
        String schema = "public";
        String tbl = table;
        int dot = table.indexOf('.');
        if (dot > 0) {
            schema = table.substring(0, dot);
            tbl = table.substring(dot + 1);
        }
        String sql = "SELECT c.ordinal_position, c.column_name, c.data_type, c.is_nullable,"
                + " c.column_default, col_description(pgc.oid, c.ordinal_position)"
                + " FROM information_schema.columns c"
                + " JOIN pg_class pgc ON pgc.relname = c.table_name"
                + " JOIN pg_namespace pgn ON pgn.oid = pgc.relnamespace AND pgn.nspname = c.table_schema"
                + " WHERE c.table_schema = ? AND c.table_name = ?"
                + " ORDER BY c.ordinal_position";
        Map<String, String> keys = columnKeys(r, database, schema, tbl);
        try (Connection conn = open(r, database);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tbl);
            try (ResultSet rs = ps.executeQuery()) {
                List<TableColumn> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new TableColumn(rs.getInt(1), rs.getString(2), rs.getString(3),
                            "YES".equalsIgnoreCase(rs.getString(4)),
                            keys.getOrDefault(rs.getString(2), ""),
                            rs.getString(5), blankToNull(rs.getString(6))));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new SqlauditException(ErrorCode.META_QUERY_FAILED,
                    "表结构查询失败（表 " + table + "）: " + e.getMessage(), e);
        }
    }

    /** PG 主键 / 唯一键映射：列名 → PRI（主键，优先）/ UNI（唯一约束）。 */
    private Map<String, String> columnKeys(Resolved r, String database, String schema, String table) {
        String sql = "SELECT kcu.column_name, tc.constraint_type"
                + " FROM information_schema.key_column_usage kcu"
                + " JOIN information_schema.table_constraints tc"
                + "   ON tc.constraint_name = kcu.constraint_name"
                + "  AND tc.table_schema = kcu.table_schema"
                + "  AND tc.table_name = kcu.table_name"
                + " WHERE kcu.table_schema = ? AND kcu.table_name = ?"
                + "   AND tc.constraint_type IN ('PRIMARY KEY', 'UNIQUE')"
                + " ORDER BY kcu.ordinal_position";
        Map<String, String> keys = new java.util.LinkedHashMap<>();
        try (Connection conn = open(r, database);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String col = rs.getString(1);
                    String type = rs.getString(2);
                    String code = "PRIMARY KEY".equalsIgnoreCase(type) ? "PRI" : "UNI";
                    // 主键优先；同名多唯一约束只留第一个
                    keys.merge(col, code, (old, fresh) -> "PRI".equals(old) ? old : fresh);
                }
            }
        } catch (SQLException e) {
            // 键信息拿不到不阻断表结构展示（键列置空），结构本体查询失败才会报 400
            log.warn("[metadata] PG 键信息查询失败 schema={} table={}: {}", schema, table, e.getMessage());
        }
        return keys;
    }

    /** 空串 / 空白注释归一为 null（前端统一按「无」渲染）。 */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ------------------------------------------------------------------
    // IoTDB 库表浏览（树/表双模型，按实例 iotdbSqlDialect 分流）
    // ------------------------------------------------------------------

    /** 实例 IoTDB SQL 方言（null 按 TREE）。 */
    private static IotdbSqlDialect iotdbSqlDialectOf(Resolved r) {
        return r.instance().getIotdbSqlDialect() == null
                ? IotdbSqlDialect.TREE : r.instance().getIotdbSqlDialect();
    }

    /** IoTDB 路径/标识符净化：只允许路径字符，防 {@code SHOW <prefix>.*} / {@code DESC a.b} 注入。 */
    private static String sanitizeIotdbPath(String s) {
        if (s == null || !s.matches("[a-zA-Z0-9_.\\-]+")) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "非法 IoTDB 路径/标识符: " + s);
        }
        return s;
    }

    /** IoTDB 库清单：{@code SHOW DATABASES}，过滤表模型内置 {@code information_schema}。 */
    private List<String> listIotdbDatabases(Resolved r) {
        try (Connection conn = open(r, null);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW DATABASES")) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                String name = rs.getString(1);
                if ("information_schema".equalsIgnoreCase(name)) {
                    continue;
                }
                out.add(name);
            }
            return out;
        } catch (SQLException e) {
            throw new SqlauditException(ErrorCode.META_QUERY_FAILED, "库列表查询失败: " + e.getMessage(), e);
        }
    }

    /** IoTDB 表清单：表模型查 {@code information_schema.tables}（BASE TABLE）；树模型查 {@code SHOW DEVICES <db>.*}。 */
    private List<TableRef> listIotdbTables(Resolved r, String database) {
        if (iotdbSqlDialectOf(r) == IotdbSqlDialect.TABLE) {
            String sql = "SELECT table_name, comment FROM information_schema.tables"
                    + " WHERE database = ? AND table_type = 'BASE TABLE' ORDER BY table_name";
            try (Connection conn = open(r, null);
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, database);
                try (ResultSet rs = ps.executeQuery()) {
                    List<TableRef> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new TableRef(rs.getString(1), blankToNull(rs.getString(2))));
                    }
                    return out;
                }
            } catch (SQLException e) {
                throw new SqlauditException(ErrorCode.META_QUERY_FAILED, "表列表查询失败: " + e.getMessage(), e);
            }
        }
        String sql = "SHOW DEVICES " + sanitizeIotdbPath(database) + ".*";
        try (Connection conn = open(r, null);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            List<TableRef> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new TableRef(rs.getString(1), null));
            }
            return out;
        } catch (SQLException e) {
            throw new SqlauditException(ErrorCode.META_QUERY_FAILED, "设备列表查询失败: " + e.getMessage(), e);
        }
    }

    /** IoTDB 列清单：表模型 {@code DESC <db>.<table>}；树模型 {@code SHOW TIMESERIES <device>.*} 取测点名。 */
    private List<String> listIotdbColumns(Resolved r, String database, String table) {
        if (iotdbSqlDialectOf(r) == IotdbSqlDialect.TABLE) {
            String sql = "DESC " + sanitizeIotdbPath(database) + "." + sanitizeIotdbPath(table);
            return iotdbFirstColumn(r, sql, table);
        }
        String device = sanitizeIotdbPath(table);
        String sql = "SHOW TIMESERIES " + device + ".*";
        try (Connection conn = open(r, null);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                String ts = rs.getString(1);
                out.add(ts.startsWith(device + ".") ? ts.substring(device.length() + 1) : ts);
            }
            return out;
        } catch (SQLException e) {
            throw new SqlauditException(ErrorCode.META_QUERY_FAILED,
                    "测点列表查询失败（设备 " + table + "）: " + e.getMessage(), e);
        }
    }

    /** 只取第一列的结果集（表模型 DESC / 树模型 SHOW 通用）。 */
    private List<String> iotdbFirstColumn(Resolved r, String sql, String label) {
        try (Connection conn = open(r, null);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new SqlauditException(ErrorCode.META_QUERY_FAILED,
                    "列列表查询失败（表 " + label + "）: " + e.getMessage(), e);
        }
    }

    /** IoTDB 表结构：表模型 {@code DESC}（ColumnName/DataType/Category）；树模型 {@code SHOW TIMESERIES}（测点 + 类型）。 */
    private List<TableColumn> iotdbTableStructure(Resolved r, String database, String table) {
        if (iotdbSqlDialectOf(r) == IotdbSqlDialect.TABLE) {
            String sql = "DESC " + sanitizeIotdbPath(database) + "." + sanitizeIotdbPath(table);
            try (Connection conn = open(r, null);
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                List<TableColumn> out = new ArrayList<>();
                int ord = 0;
                while (rs.next()) {
                    // DESC 返回 ColumnName / DataType / Category（TAG/FIELD/ATTRIBUTE/TIME）
                    out.add(new TableColumn(++ord, rs.getString(1), rs.getString(2), true, "", null, null));
                }
                return out;
            } catch (SQLException e) {
                throw new SqlauditException(ErrorCode.META_QUERY_FAILED,
                        "表结构查询失败（表 " + table + "）: " + e.getMessage(), e);
            }
        }
        String device = sanitizeIotdbPath(table);
        String sql = "SHOW TIMESERIES " + device + ".*";
        try (Connection conn = open(r, null);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            List<TableColumn> out = new ArrayList<>();
            int ord = 0;
            while (rs.next()) {
                String ts = rs.getString(1);
                String name = ts.startsWith(device + ".") ? ts.substring(device.length() + 1) : ts;
                // SHOW TIMESERIES 列序：Timeseries / Alias / Database / DataType / ...
                out.add(new TableColumn(++ord, name, rs.getString(4), true, "", null, null));
            }
            return out;
        } catch (SQLException e) {
            throw new SqlauditException(ErrorCode.META_QUERY_FAILED,
                    "测点结构查询失败（设备 " + table + "）: " + e.getMessage(), e);
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

    /** 表引用（契约 §6：{name, comment}；comment 为原库原生注释，可为 null）。 */
    public record TableRef(String name, String comment) {
    }

    /**
     * 表结构列（契约 §6：{ordinal, name, dataType, nullable, key, defaultValue, comment}）。
     *
     * <p>{@code key} 取值：MySQL {@code PRI / UNI / MUL / 空串}，PG {@code PRI / UNI / 空串}；
     * {@code defaultValue} / {@code comment} 无值为 null。</p>
     */
    public record TableColumn(int ordinal, String name, String dataType, boolean nullable,
                              String key, String defaultValue, String comment) {
    }

    // ------------------------------------------------------------------
    // JDBC 连接
    // ------------------------------------------------------------------

    /** 按解析结果开 JDBC 连接（DriverManager，loginTimeout ≤5s；调用方负责关闭）。 */
    private static Connection open(Resolved r, String database) throws SQLException {
        ensureDriverLoaded(r.dialect());
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        return DriverManager.getConnection(
                jdbcUrl(r.dialect(), r.instance().getHost(), portOf(r), database,
                        r.instance().getIotdbSqlDialect()),
                r.credential().getUsername(), r.password());
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
        if (dialect == Dialect.PG) {
            return 5432;
        }
        if (dialect == Dialect.IOTDB) {
            return 6667;
        }
        return 3306;
    }

    /** 拼 JDBC URL（库名可为空；超时参数按方言单位：MySQL 毫秒、PG 秒）。 */
    private static String jdbcUrl(Dialect dialect, String host, int port, String database,
                                  IotdbSqlDialect iotdbSqlDialect) {
        String db = database == null || database.isBlank() ? "" : database;
        if (dialect == Dialect.PG) {
            return "jdbc:postgresql://" + host + ":" + port + "/" + db
                    + "?connectTimeout=" + LOGIN_TIMEOUT_SECONDS + "&socketTimeout=30";
        }
        if (dialect == Dialect.IOTDB) {
            // IoTDB 2.x JDBC：URL 不带库名；树/表双模型是连接级方言开关（默认 tree），
            // 表模型须 ?sql_dialect=table
            return iotdbSqlDialect == IotdbSqlDialect.TABLE
                    ? "jdbc:iotdb://" + host + ":" + port + "?sql_dialect=table"
                    : "jdbc:iotdb://" + host + ":" + port + "/";
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
