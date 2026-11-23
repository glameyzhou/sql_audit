package com.sqlaudit.server.gateway;

import com.sqlaudit.common.crypto.CredentialCrypto;
import com.sqlaudit.common.enums.AccountType;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.executor.gateway.ProductionGateway;
import com.sqlaudit.storage.dao.CredentialDao;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.model.Credential;
import com.sqlaudit.storage.model.Instance;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * server 端生产库执行网关（回滚执行用，实现 {@link ProductionGateway}）。
 *
 * <p>按实例路由建 JDBC 连接池，单事务内执行 DML。回滚执行走「无前镜像」路径：
 * 反向语句通常带主键 WHERE、逐行幂等，直接执行并返回 affected 由调用方校验
 * （不做 SELECT FOR UPDATE，§5.6 回放阶段）。仍保留前镜像路径（preimageSql 非空时
 * SELECT → DML → affected==preimage 对账）以保证接口契约完整。</p>
 *
 * <p>RW_EXEC 凭据从 {@link CredentialDao} 读取，密文经 {@link CredentialCrypto} 解密
 * （密钥来自环境变量 SQLAUDIT_CREDENTIAL_KEY，缺失时用本地开发默认密钥）。</p>
 */
public class JdbcProductionGateway implements ProductionGateway {

    private final InstanceDao instanceDao;
    private final CredentialDao credentialDao;

    /** 按实例 id 缓存的连接池，避免每次执行重建连接。 */
    private final ConcurrentHashMap<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    public JdbcProductionGateway(InstanceDao instanceDao, CredentialDao credentialDao) {
        this.instanceDao = instanceDao;
        this.credentialDao = credentialDao;
    }

    @Override
    public ExecutionResult executeInTransaction(ExecutionRequest request) {
        Instance inst = instanceDao.findById(request.instanceId());
        if (inst == null) {
            return ExecutionResult.executionError("实例不存在: id=" + request.instanceId());
        }
        Dialect dialect = dialectOf(inst);
        if (dialect == Dialect.IOTDB) {
            return ExecutionResult.executionError("IoTDB 实例不支持 JDBC 回滚执行: " + inst.getName());
        }

        Credential cred = credentialDao.findByInstanceAndType(request.instanceId(), AccountType.RW_EXEC);
        if (cred == null) {
            return ExecutionResult.executionError("实例缺少 RW_EXEC 凭据: id=" + request.instanceId());
        }
        String password;
        try {
            password = CredentialCrypto.decrypt(cred.getPasswordEnc());
        } catch (Exception e) {
            return ExecutionResult.executionError("RW_EXEC 凭据解密失败: " + e.getMessage());
        }

        HikariDataSource ds = poolFor(inst, dialect, cred.getUsername(), password);
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (request.preimageSql() != null) {
                    // 前镜像路径：SELECT → DML → affected==preimage 对账（§5.3）
                    List<Map<String, Object>> preimage = readPreimage(conn, request.preimageSql());
                    long affected = executeUpdate(conn, request.statementSql());
                    if (affected != preimage.size()) {
                        conn.rollback();
                        return ExecutionResult.reconcileFailure(preimage.size(), affected);
                    }
                    conn.commit();
                    return ExecutionResult.success(preimage, affected, null);
                }
                // 无前镜像路径（回滚反向语句）：直接执行，affected 由调用方校验（>0）
                long affected = executeUpdate(conn, request.statementSql());
                conn.commit();
                return ExecutionResult.success(null, affected, null);
            } catch (Exception e) {
                conn.rollback();
                return ExecutionResult.executionError(String.valueOf(e.getMessage()));
            }
        } catch (SQLException e) {
            return ExecutionResult.executionError("连接失败: " + e.getMessage());
        }
    }

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

    private static long executeUpdate(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            return ps.executeUpdate();
        }
    }

    private HikariDataSource poolFor(Instance inst, Dialect dialect, String username, String password) {
        return pools.computeIfAbsent(inst.getId(), id -> {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(jdbcUrl(inst, dialect));
            cfg.setUsername(username);
            cfg.setPassword(password);
            cfg.setMaximumPoolSize(4);
            cfg.setConnectionTimeout(5000);
            return new HikariDataSource(cfg);
        });
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
                + "?useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=false"
                + "&connectTimeout=3000&socketTimeout=30000";
    }

    /**
     * 实例方言：列值已由存储层按存储码解码为 {@link Dialect}（POSTGRESQL/POSTGRES 等别名归一
     * 在入库前完成），为空时回落 MYSQL。
     */
    private static Dialect dialectOf(Instance inst) {
        return inst.getDialect() == null ? Dialect.MYSQL : inst.getDialect();
    }
}
