package com.sqlaudit.worker.gateway;

import com.sqlaudit.common.crypto.CredentialCrypto;
import com.sqlaudit.common.crypto.CredentialCryptoException;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.executor.gate.ExecutionGate;
import com.sqlaudit.storage.dao.CredentialDao;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.model.Credential;
import com.sqlaudit.storage.model.Instance;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 结构指纹提供者（§3.5 结构漂移门禁的数据源）：按实例 + 表重算当前 DDL 结构指纹。
 *
 * <p>执行前门禁把「审核时记录的 {@code ddl_fingerprint}」与「执行前的当前结构」比对，
 * 重算需要连生产库（RO_META 只读凭据）取 DDL 文本：</p>
 * <ul>
 *   <li>MySQL：{@code SHOW CREATE TABLE}，取结果第二列（Create Table 列）；</li>
 *   <li>PG：information_schema.columns 聚合重建表结构骨架（PG 16 无 pg_get_tabledef）；</li>
 *   <li>IoTDB：无 SHOW CREATE 等价物 → 返回常量 {@link #IOTDB_NO_FINGERPRINT}
 *       （设计取舍见下）。</li>
 * </ul>
 * <p>返回值 = DDL 文本的 SHA-256 hex（{@link java.security.MessageDigest}）。
 * 表不存在 / 空结果返回 {@code null}（门禁侧按「结构漂移」拦截）；
 * 实例不存在、RO_META 凭据缺失、连接失败均抛 {@link IllegalStateException}。</p>
 *
 * <p>连接池按实例 id 缓存（惰性创建，与同包 {@link JdbcProductionGateway} 同风格）；
 * 凭据密文经 {@code CredentialCrypto}（AES-GCM）解密后建连接。</p>
 *
 * <p><strong>IoTDB 设计取舍</strong>：IoTDB 没有 SHOW CREATE TABLE 等价的结构导出，
 * 取不到结构指纹。返回常量让审核期与执行期「永远一致」——结构漂移门禁对 IoTDB 实例
 * 恒不触发；结构的意外变更由审核期 IoTDB 白名单（§4.2）与快照恢复 SOP（§5.7）兜底，
 * 此取舍已在常量命名中显式标注，避免误读为「指纹相同」。</p>
 */
public class JdbcStructureFingerprintProvider implements ExecutionGate.StructureFingerprintProvider {

    /** IoTDB 无结构导出能力时的常量指纹（设计取舍，见类 javadoc）。 */
    public static final String IOTDB_NO_FINGERPRINT = "iotdb-no-fingerprint";

    private final InstanceDao instanceDao;
    private final CredentialDao credentialDao;

    /** 按实例 id 缓存的连接池，避免每次取指纹重建连接。 */
    private final ConcurrentHashMap<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    public JdbcStructureFingerprintProvider(InstanceDao instanceDao, CredentialDao credentialDao) {
        this.instanceDao = instanceDao;
        this.credentialDao = credentialDao;
    }

    @Override
    public String currentFingerprint(long instanceId, String schema, String table) {
        if (table == null || table.isBlank()) {
            throw new IllegalStateException("表名为空，无法取结构指纹: instanceId=" + instanceId);
        }
        // 1. 实例路由：查不到实例不连生产库
        Instance inst = instanceDao.findById(instanceId);
        if (inst == null) {
            throw new IllegalStateException("实例不存在: id=" + instanceId);
        }
        Dialect dialect = dialectOf(inst);
        if (dialect == Dialect.IOTDB) {
            return IOTDB_NO_FINGERPRINT;
        }

        // 2. RO_META 凭据（§3.5：结构指纹走只读元数据账号，与执行账号物理隔离）
        Credential cred = credentialDao.findByInstanceAndType(instanceId, "RO_META");
        if (cred == null) {
            throw new IllegalStateException("实例缺少 RO_META 凭据: id=" + instanceId);
        }

        // 3. 取 DDL 文本 → SHA-256（表不存在返回 null）
        String ddl = readDdl(poolFor(inst, cred), dialect, schema, table, inst.getDefaultSchema());
        return ddl == null ? null : sha256Hex(ddl);
    }

    // ------------------------------------------------------------------
    // DDL 读取（分方言）
    // ------------------------------------------------------------------

    private static String readDdl(HikariDataSource ds, Dialect dialect, String schema,
                                  String table, String defaultSchema) {
        try (Connection conn = ds.getConnection()) {
            if (dialect == Dialect.MYSQL) {
                String schemaPart = schema != null ? schema : defaultSchema;
                String target = (schemaPart == null ? "" : "`" + schemaPart + "`.") + "`" + table + "`";
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SHOW CREATE TABLE " + target)) {
                    if (!rs.next()) {
                        return null;
                    }
                    return rs.getString(2);
                } catch (SQLException e) {
                    // MySQL 对不存在的表抛错（errorCode 1146 / SQLSTATE 42S02）而非空结果集，
                    // 转译为 null（表不存在）；其余 SQL 错误继续上抛
                    if ("42S02".equals(e.getSQLState())) {
                        return null;
                    }
                    throw e;
                }
            }
            // PG：information_schema.columns 聚合重建表结构骨架（列名 + 类型 + 非空）。
            // 设计文档原写 pg_get_tabledef——实测 PG 16 已无此函数（was removed），
            // 改用 information_schema（PG 官方稳定视图）；骨架不含约束/索引，
            // 漂移检测主目标是「列级结构变化」（增删列/改类型），够用且确定性好。
            // 表不存在时 string_agg 结果为空集 → rs.next() false → null。
            String effectiveSchema = schema != null ? schema : defaultSchema;
            String schemaCond = effectiveSchema != null && !effectiveSchema.isBlank()
                    ? effectiveSchema : "public";
            String sql = "SELECT string_agg(quote_ident(column_name) || ' ' || udt_name"
                    + " || CASE WHEN is_nullable = 'NO' THEN ' NOT NULL' ELSE '' END,"
                    + " ',' ORDER BY ordinal_position)"
                    + " FROM information_schema.columns"
                    + " WHERE table_schema = '" + schemaCond.replace("'", "''") + "'"
                    + " AND table_name = '" + table.replace("'", "''") + "'";
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                if (!rs.next() || rs.getObject(1) == null) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("取结构定义失败: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 连接池与凭据
    // ------------------------------------------------------------------

    /** 按实例缓存的连接池（惰性创建；与 {@link JdbcProductionGateway} 同风格）。 */
    private HikariDataSource poolFor(Instance inst, Credential cred) {
        try {
            return pools.computeIfAbsent(inst.getId(), id -> {
                HikariConfig cfg = new HikariConfig();
                cfg.setJdbcUrl(jdbcUrl(inst, dialectOf(inst)));
                cfg.setUsername(cred.getUsername());
                cfg.setPassword(plainPassword(cred));
                cfg.setMaximumPoolSize(4);
                cfg.setConnectionTimeout(5000);
                return new HikariDataSource(cfg);
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("实例连接池创建失败: id=" + inst.getId()
                    + ", " + e.getMessage(), e);
        }
    }

    /** RO_META 凭据密文解密（AES-GCM；失败视为连接前置条件缺失）。 */
    private static String plainPassword(Credential cred) {
        try {
            return CredentialCrypto.decrypt(cred.getPasswordEnc());
        } catch (CredentialCryptoException e) {
            throw new IllegalStateException("RO_META 凭据解密失败: " + e.getMessage(), e);
        }
    }

    private static Dialect dialectOf(Instance inst) {
        if (inst.getDialect() == null) {
            return Dialect.MYSQL;
        }
        String dialect = inst.getDialect().trim().toUpperCase();
        // 别名归一：UI/种子数据可能写入 POSTGRESQL/POSTGRES，统一映射枚举 PG，
        // 避免未知字符串静默回落 MySQL（错方言会用错驱动连库）
        if ("POSTGRESQL".equals(dialect) || "POSTGRES".equals(dialect)) {
            return Dialect.PG;
        }
        try {
            return Dialect.valueOf(dialect);
        } catch (IllegalArgumentException e) {
            return Dialect.MYSQL;
        }
    }

    private static String jdbcUrl(Instance inst, Dialect dialect) {
        String host = inst.getHost();
        int port = inst.getPort() == null ? 3306 : inst.getPort();
        String schema = inst.getDefaultSchema() == null ? "" : inst.getDefaultSchema();
        if (dialect == Dialect.PG) {
            return "jdbc:postgresql://" + host + ":" + port + "/" + schema
                    + "?connectTimeout=3&socketTimeout=30";
        }
        return "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&connectTimeout=3000&socketTimeout=30000";
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** DDL 文本 → SHA-256 hex（小写）。 */
    private static String sha256Hex(String text) {
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
}
