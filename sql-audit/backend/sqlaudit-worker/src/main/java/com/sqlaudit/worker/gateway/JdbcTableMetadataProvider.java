package com.sqlaudit.worker.gateway;

import com.sqlaudit.common.crypto.CredentialCrypto;
import com.sqlaudit.common.crypto.CredentialCryptoException;
import com.sqlaudit.common.enums.AccountType;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.rule.TableMetadataProvider;
import com.sqlaudit.storage.dao.CredentialDao;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.model.Credential;
import com.sqlaudit.storage.model.Instance;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生产库表元数据真实现（RO_META 只读凭据 + information_schema）。
 *
 * <p>取代 W2 联调期 {@code ServerBeansConfig} 的 dev stub（硬编码「id/a/b」列），
 * 在 mysql/postgres 两个真库方言 profile 下恒装配：前镜像列清单、INSERT 反向主键、
 * 规则引擎（R_NO_PK / R_OBJECT_NOT_ALLOWED）都以真实表结构为准。</p>
 *
 * <ul>
 *   <li>列：{@code information_schema.columns}（ordinal 排序）；</li>
 *   <li>主键：{@code key_column_usage} constraint_name='PRIMARY'（MySQL/PG 通用）；</li>
 *   <li>生成列：MySQL 看 {@code extra} 含 GENERATED；PG 12+ 看 {@code is_generated='ALWAYS'}
 *       ——反向 INSERT 必须排除（README §4.4）；</li>
 *   <li>掩码列 / 对象白名单未接线（masking_rule 与白名单配置属后续），返回空集 / true。</li>
 * </ul>
 *
 * <p>表不存在返回 {@code null}（R_OBJECT_NOT_ALLOWED 接住）；连接/查询失败返回
 * {@code null} 并 log.warn（依赖元数据的规则按「无法证明安全」保守处理——
 * {@code R_OBJECT_NOT_ALLOWED} 阻断，不会静默放行）。</p>
 */
public class JdbcTableMetadataProvider implements TableMetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcTableMetadataProvider.class);

    private final InstanceDao instanceDao;
    private final CredentialDao credentialDao;

    /** 按实例 id 缓存的连接池（惰性创建；与同包网关同风格，凭据轮换需重启）。 */
    private final ConcurrentHashMap<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    public JdbcTableMetadataProvider(InstanceDao instanceDao, CredentialDao credentialDao) {
        this.instanceDao = instanceDao;
        this.credentialDao = credentialDao;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public TableMetadata getTable(String schema, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        // 名字来源不一（解析器对象已剥离、AST 原样带反引号/双引号），这里统一剥离，
        // 否则 information_schema 查不到带引号的名字（对不存在表静默返回 null）
        schema = stripQuotes(schema);
        name = stripQuotes(name);
        Instance inst = resolveInstance(schema, name);
        if (inst == null) {
            return null;
        }
        Dialect dialect = dialectOf(inst);
        if (dialect == Dialect.IOTDB) {
            // IoTDB 无 information_schema 等价物：不承诺元数据，返回 null（白名单 SOP 兜底）
            return null;
        }
        // 第二道防线（第一道在提交门禁 SchemaBoundaryChecker）：MySQL 下实例配置了默认库、
        // 语句却引用其他 schema → 表按不存在处理（R_OBJECT_NOT_ALLOWED 会接住）。
        // 默认库未配置时不限制；PG 不校验（defaultSchema=URL database，schema 前缀是库内命名空间）。
        if (dialect == Dialect.MYSQL && schema != null && !schema.isBlank()) {
            String def = inst.getDefaultSchema();
            if (def != null && !def.isBlank() && !def.equalsIgnoreCase(schema)) {
                log.warn("[metadata] 实例 {} 语句引用默认库之外的 schema（{}），按表缺失处理",
                        inst.getId(), schema);
                return null;
            }
        }
        Credential cred;
        try {
            cred = credentialDao.findByInstanceAndType(inst.getId(), AccountType.RO_META);
            if (cred == null) {
                log.warn("[metadata] 实例 {} 缺少 RO_META 凭据，元数据不可用（按表缺失处理）", inst.getId());
                return null;
            }
        } catch (CredentialCryptoException e) {
            log.warn("[metadata] RO_META 凭据解密失败: {}", e.getMessage());
            return null;
        }
        try (Connection conn = poolFor(inst, cred).getConnection()) {
            String effectiveSchema = schema != null && !schema.isBlank()
                    ? schema : inst.getDefaultSchema();
            if (effectiveSchema == null || effectiveSchema.isBlank()) {
                effectiveSchema = dialect == Dialect.PG ? "public" : effectiveSchema;
            }
            if (effectiveSchema == null || effectiveSchema.isBlank()) {
                log.warn("[metadata] 实例 {} 未配置默认库且语句无 schema，无法定位表 {}", inst.getId(), name);
                return null;
            }
            return loadTable(conn, dialect, effectiveSchema, name);
        } catch (SQLException e) {
            log.warn("[metadata] 查询表 {}.{} 元数据失败（按表缺失处理）: {}",
                    schema, name, e.getMessage());
            return null;
        }
    }

    /**
     * 实例路由：接口签名只有 (schema, name)，当前实例由
     * {@link InstanceContext}（ThreadLocal）提供——工单语义本就是「单默认实例」
     * （{@code TicketSubmissionService} 把全部 unit 挂到 defaultInstanceId），
     * 预览/提交/执行/回滚复核各入口在调用前注入当前实例 id。
     */
    private Instance resolveInstance(String schema, String name) {
        Long instanceId = InstanceContext.get();
        if (instanceId == null) {
            log.warn("[metadata] 无当前实例上下文，无法路由元数据（schema={}, table={}）", schema, name);
            return null;
        }
        Instance inst = instanceDao.findById(instanceId);
        if (inst == null) {
            log.warn("[metadata] 实例不存在: id={}", instanceId);
            return null;
        }
        return inst;
    }

    private TableMetadata loadTable(Connection conn, Dialect dialect,
                                    String schema, String name) throws SQLException {
        List<String> columns = new ArrayList<>();
        Set<String> generated = new LinkedHashSet<>();
        String colSql = "SELECT column_name, "
                + (dialect == Dialect.PG ? "COALESCE(is_generated, '')" : "COALESCE(extra, '')")
                + " FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        try (PreparedStatement ps = conn.prepareStatement(colSql)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String col = rs.getString(1);
                    String extra = rs.getString(2);
                    columns.add(col);
                    // MySQL extra 含 GENERATED；PG is_generated = 'ALWAYS'
                    if (extra != null && (extra.toUpperCase().contains("GENERATED")
                            || "ALWAYS".equalsIgnoreCase(extra))) {
                        generated.add(col);
                    }
                }
            }
        }
        if (columns.isEmpty()) {
            return null; // 表不存在
        }

        List<String> pk = new ArrayList<>();
        // 经 table_constraints 判定主键约束（MySQL/PG 通用）：PG 主键约束名是
        // <表>_pkey 而非 'PRIMARY'，只查 constraint_name='PRIMARY' 会漏掉 PG 主键
        // 不加 DISTINCT（MySQL 的 DISTINCT + 非选中列 ORDER BY 会报错）；
        // table_name join 条件已保证无重复行
        String pkSql = "SELECT kcu.column_name"
                + " FROM information_schema.key_column_usage kcu"
                + " JOIN information_schema.table_constraints tc"
                + "   ON tc.constraint_name = kcu.constraint_name"
                + "  AND tc.table_schema = kcu.table_schema"
                + "  AND tc.table_name = kcu.table_name"
                + " WHERE kcu.table_schema = ? AND kcu.table_name = ?"
                + "   AND tc.constraint_type = 'PRIMARY KEY'"
                + " ORDER BY kcu.ordinal_position";
        try (PreparedStatement ps = conn.prepareStatement(pkSql)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pk.add(rs.getString(1));
                }
            }
        }
        return new TableMetadata(!pk.isEmpty(), pk, generated, columns, Set.of(), true);
    }

    private HikariDataSource poolFor(Instance inst, Credential cred) {
        return pools.computeIfAbsent(inst.getId(), id -> {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(jdbcUrl(inst, dialectOf(inst)));
            cfg.setUsername(cred.getUsername());
            cfg.setPassword(CredentialCrypto.decrypt(cred.getPasswordEnc()));
            cfg.setMaximumPoolSize(2);
            cfg.setConnectionTimeout(5000);
            return new HikariDataSource(cfg);
        });
    }

    private static String jdbcUrl(Instance inst, Dialect dialect) {
        String host = inst.getHost();
        int port = inst.getPort() == null ? (dialect == Dialect.PG ? 5432 : 3306) : inst.getPort();
        String db = inst.getDefaultSchema() == null ? "" : inst.getDefaultSchema();
        if (dialect == Dialect.PG) {
            return "jdbc:postgresql://" + host + ":" + port + "/" + db + "?connectTimeout=5&socketTimeout=30";
        }
        return "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=30000";
    }

    private static Dialect dialectOf(Instance inst) {
        // 实例方言写入时已校验为 Dialect 枚举，未知取值到不了这里；仅保留 null 兜底
        return inst == null || inst.getDialect() == null ? Dialect.MYSQL : inst.getDialect();
    }

    /** 剥离标识符外围引号（`name` / "name" → name）；无引号原样返回。 */
    private static String stripQuotes(String name) {
        if (name == null || name.length() < 2) {
            return name;
        }
        char first = name.charAt(0);
        char last = name.charAt(name.length() - 1);
        if ((first == '`' && last == '`') || (first == '"' && last == '"')) {
            return name.substring(1, name.length() - 1);
        }
        return name;
    }

    /**
     * 当前执行上下文实例（ThreadLocal）。规则引擎/前镜像/回滚复核都在单 unit 的
     * 执行上下文内运行，由 {@code WorkerLoop}（worker 侧）与提交编排（server 侧）
     * 在调用前注入当前实例 id。
     */
    public static final class InstanceContext {
        private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

        private InstanceContext() {
        }

        public static void set(Long instanceId) {
            if (instanceId == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(instanceId);
            }
        }

        public static Long get() {
            return CURRENT.get();
        }
    }
}
