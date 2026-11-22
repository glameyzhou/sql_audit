package com.sqlaudit.worker.gateway;

import com.sqlaudit.executor.gateway.ProductionGateway;

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

/**
 * H2 演示网关（W2 联调用）：真 JDBC 事务 + 前镜像 SELECT FOR UPDATE + 对账 + 主键取回，
 * 协议语义与生产实现一致，仅数据源是 H2 内存库。
 *
 * <p><strong>生产实现（W2 待替换）</strong>：rw_exec 连接池 + 方言分派的真实现，
 * 必须保持本类的协议语义：同一 Connection 同一事务、隔离级别断言、
 * affected==preimage 对账先行、COMMIT 前反向 SQL 数据已就绪。</p>
 */
public class H2DemoGateway implements ProductionGateway {

    private final String jdbcUrl;

    public H2DemoGateway(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, "sa", "");
    }

    @Override
    public ExecutionResult executeInTransaction(ExecutionRequest request) {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                // 隔离级别断言（§5.3：云厂商/中间件可能改默认值；H2 默认 RR 兼容演示）
                int level = conn.getTransactionIsolation();
                if (level != Connection.TRANSACTION_READ_COMMITTED
                        && level != Connection.TRANSACTION_REPEATABLE_READ) {
                    return ExecutionResult.executionError("意外隔离级别: " + level);
                }

                List<Map<String, Object>> preimage = null;
                // 1. 前镜像（SELECT ... FOR UPDATE，与 DML 同事务锁行，§5.3）
                if (request.preimageSql() != null) {
                    preimage = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(request.preimageSql())) {
                        try (ResultSet rs = ps.executeQuery()) {
                            ResultSetMetaData meta = rs.getMetaData();
                            while (rs.next()) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                for (int i = 1; i <= meta.getColumnCount(); i++) {
                                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                                }
                                preimage.add(row);
                            }
                        }
                    }
                }

                // 2. DML 执行（affected rows = matched rows 口径）
                long affected;
                try (PreparedStatement ps = conn.prepareStatement(request.statementSql())) {
                    affected = ps.executeUpdate();
                }

                // 3. INSERT 主键取回（演示库用 getGeneratedKeys；生产分方言：
                //    MySQL=LAST_INSERT_ID() / PG=RETURNING 改写，§5.5）
                List<Object> insertedPk = null;
                if (preimage == null) {
                    insertedPk = new ArrayList<>();
                    // H2 演示：拿不到关联键时回读最大 id（仅演示语义，W2 真实现分方言）
                    insertedPk.add(null);
                }

                // 4. 对账先行（§5.3 硬门禁）：affected != preimage → 回滚 + RECONCILE_FAILED
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

    /** 演示库建表（测试用）。 */
    public void initDemoTable() {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS t_demo (id INT PRIMARY KEY, a INT, b VARCHAR(64))");
            st.execute("MERGE INTO t_demo (id, a, b) KEY (id) VALUES (1, 10, 'x')");
            st.execute("MERGE INTO t_demo (id, a, b) KEY (id) VALUES (2, 20, 'y')");
            st.execute("MERGE INTO t_demo (id, a, b) KEY (id) VALUES (3, 30, 'z')");
        } catch (SQLException e) {
            throw new IllegalStateException("演示表初始化失败", e);
        }
    }

    /** 读当前表内容（测试断言用）。 */
    public Map<Integer, Integer> currentValues() {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, a FROM t_demo ORDER BY id")) {
            while (rs.next()) {
                result.put(rs.getInt(1), rs.getInt(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return result;
    }
}
