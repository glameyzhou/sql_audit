package com.sqlaudit.server.config;

import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.dao.AuditDao;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlApprovalDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlAuditDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlExecLockDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlRollbackDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlTicketDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlTicketUnitDao;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MySQL 生产装配（profile=mysql）：JDBC 真 DAO 接管存储 bean。
 *
 * <p>与 {@link ServerBeansConfig}（memory，默认）互斥；其余服务 bean
 * （workflow/audit/query/提交编排）在两套 profile 下装配完全一致——存储切换
 * 对业务层零感知（依赖 DAO 接口，W0-3 冻结的契约价值在此兑现）。</p>
 *
 * <p>启动：{@code SPRING_PROFILES_ACTIVE=mysql java -jar sqlaudit-server.jar}。
 * 连接经环境变量覆盖：SQLAUDIT_DB_URL / SQLAUDIT_DB_USER / SQLAUDIT_DB_PASSWORD
 * （默认值指向本机 Docker MySQL，见 application.yml）。Flyway 自动迁移已开启——
 * 首次启动执行 V1__init.sql 建 23 表。</p>
 */
@Configuration
@Profile("mysql")
public class MysqlProfileConfig {

    @Bean
    public MySqlTicketUnitDao jdbcTicketUnitDao(JdbcTemplate jdbcTemplate) {
        return new MySqlTicketUnitDao(jdbcTemplate);
    }

    @Bean
    public TicketDao ticketDao(JdbcTemplate jdbcTemplate) {
        return new MySqlTicketDao(jdbcTemplate);
    }

    @Bean
    public ApprovalDao approvalDao(JdbcTemplate jdbcTemplate) {
        return new MySqlApprovalDao(jdbcTemplate);
    }

    @Bean
    public AuditDao auditDao(JdbcTemplate jdbcTemplate) {
        return new MySqlAuditDao(jdbcTemplate);
    }

    @Bean
    public MySqlRollbackDao rollbackDao(JdbcTemplate jdbcTemplate) {
        return new MySqlRollbackDao(jdbcTemplate);
    }

    @Bean
    public MySqlExecLockDao execLockDao(JdbcTemplate jdbcTemplate) {
        return new MySqlExecLockDao(jdbcTemplate);
    }
}
