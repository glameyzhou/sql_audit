package com.sqlaudit.storage.impl.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * JDBC DAO 的「多语句写操作必须原子」辅助（整体替换授权、审批转交等）。
 *
 * <p>为什么不让调用方自己开事务就好：删旧 + 插新中途失败会留下「零权限角色」
 * 或「少一个审批人的组」这类<strong>看起来正常、实际已经坏掉</strong>的状态，
 * 所以原子性必须由 DAO 自己兜底，而不是指望每个调用点都记得加事务。</p>
 *
 * <p>传播行为是 {@code PROPAGATION_REQUIRED}：有外层 Spring 事务（服务层的
 * {@code @Transactional}，例如 RBAC 防自锁那一整笔）时直接加入，没有才自己开一个。
 * {@link JdbcTemplate} 取连接走 {@code DataSourceUtils}，天然感知当前事务，
 * 所以同一个 DAO 的多条语句一定落在同一连接上。</p>
 */
public final class JdbcTx {

    private JdbcTx() {
    }

    /** 为某个 JdbcTemplate 建事务模板（PROPAGATION_REQUIRED，默认隔离级别）。 */
    public static TransactionTemplate of(JdbcTemplate jdbc) {
        DataSource dataSource = Objects.requireNonNull(jdbc.getDataSource(), "JdbcTemplate 未绑定 DataSource");
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /** 在事务内执行并返回结果（{@code null} 结果安全）。 */
    public static <T> T inTx(TransactionTemplate tx, Supplier<T> action) {
        return tx.execute(status -> action.get());
    }
}
