package com.sqlaudit.server.config;

import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.dao.AuditDao;
import com.sqlaudit.storage.dao.CredentialDao;
import com.sqlaudit.storage.dao.FlowRuleDao;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.dao.PermissionDao;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.dao.RuleDao;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresApprovalDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresAuditDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresCredentialDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresExecLockDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresFlowRuleDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresInstanceDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresPermissionDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresRoleDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresRollbackDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresRuleDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresTicketDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresTicketUnitDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresUserDao;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL 生产装配（profile=postgres）：JDBC 真 DAO 接管存储 bean。
 *
 * <p>与 {@link MysqlProfileConfig} / {@link H2ProfileConfig} 同构：
 * {@code @Profile("postgres")} 注册 6 个 Postgres* DAO bean，其余服务 bean
 * （workflow/audit/query/提交编排）在各 profile 下装配完全一致——存储切换
 * 对业务层零感知。</p>
 *
 * <p>启动：{@code SPRING_PROFILES_ACTIVE=postgres java -jar sqlaudit-server.jar}。
 * 连接经环境变量覆盖：SQLAUDIT_PG_URL / SQLAUDIT_PG_USER / SQLAUDIT_PG_PASSWORD
 * （默认值指向本机 PG，见 application-postgres.yml）。Flyway 自动迁移已开启——
 * 首次启动执行 PG V1__init.sql 建 23 表。</p>
 */
@Configuration
@Profile("postgres")
public class PostgresProfileConfig {

    @Bean
    public PostgresTicketUnitDao postgresTicketUnitDao(JdbcTemplate jdbcTemplate) {
        return new PostgresTicketUnitDao(jdbcTemplate);
    }

    @Bean
    public TicketDao ticketDao(JdbcTemplate jdbcTemplate) {
        return new PostgresTicketDao(jdbcTemplate);
    }

    @Bean
    public ApprovalDao approvalDao(JdbcTemplate jdbcTemplate) {
        return new PostgresApprovalDao(jdbcTemplate);
    }

    @Bean
    public AuditDao auditDao(JdbcTemplate jdbcTemplate) {
        return new PostgresAuditDao(jdbcTemplate);
    }

    @Bean
    public PostgresRollbackDao rollbackDao(JdbcTemplate jdbcTemplate) {
        return new PostgresRollbackDao(jdbcTemplate);
    }

    @Bean
    public PostgresExecLockDao execLockDao(JdbcTemplate jdbcTemplate) {
        return new PostgresExecLockDao(jdbcTemplate);
    }

    @Bean
    public UserDao userDao(JdbcTemplate jdbcTemplate) {
        return new PostgresUserDao(jdbcTemplate);
    }

    @Bean
    public RoleDao roleDao(JdbcTemplate jdbcTemplate) {
        return new PostgresRoleDao(jdbcTemplate);
    }

    @Bean
    public PermissionDao permissionDao(JdbcTemplate jdbcTemplate) {
        return new PostgresPermissionDao(jdbcTemplate);
    }

    @Bean
    public InstanceDao instanceDao(JdbcTemplate jdbcTemplate) {
        return new PostgresInstanceDao(jdbcTemplate);
    }

    @Bean
    public RuleDao ruleDao(JdbcTemplate jdbcTemplate) {
        return new PostgresRuleDao(jdbcTemplate);
    }

    @Bean
    public FlowRuleDao flowRuleDao(JdbcTemplate jdbcTemplate) {
        return new PostgresFlowRuleDao(jdbcTemplate);
    }

    @Bean
    public CredentialDao credentialDao(JdbcTemplate jdbcTemplate) {
        return new PostgresCredentialDao(jdbcTemplate);
    }
}
