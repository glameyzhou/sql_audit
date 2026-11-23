package com.sqlaudit.server.config;

import com.sqlaudit.common.crypto.SqlCipher;
import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.dao.AuditDao;
import com.sqlaudit.storage.dao.CredentialDao;
import com.sqlaudit.storage.dao.FlowNodeDao;
import com.sqlaudit.storage.dao.FlowRuleDao;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.dao.PermissionDao;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.dao.RuleDao;
import com.sqlaudit.storage.dao.TableRemarkDao;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresApprovalDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresAuditDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresCredentialDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresExecLockDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresFlowNodeDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresFlowRuleDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresInstanceDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresPermissionDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresRoleDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresRollbackDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresRuleDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresTableRemarkDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresTicketDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresTicketUnitDao;
import com.sqlaudit.storage.impl.jdbc.postgresql.PostgresUserDao;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL 方言装配（profile=postgres）：JDBC 真 DAO 接管存储 bean。
 *
 * <p>与 {@link MysqlProfileConfig} 同构：{@code @Profile("postgres")} 注册 Postgres* DAO
 * bean，SQL 密文由 JDBC DAO 用 {@link SqlCipher} 透明加解密。</p>
 *
 * <p>启动：{@code SPRING_PROFILES_ACTIVE=postgres java -jar sqlaudit-server.jar}（或
 * {@code dev-company} / {@code test} / {@code prod} 组）。连接经环境变量覆盖：
 * SQLAUDIT_PG_URL / SQLAUDIT_PG_USER / SQLAUDIT_PG_PASSWORD（默认值指向本机 PG，见
 * application-postgres.yml）。Flyway 自动迁移已开启。</p>
 */
@Configuration
@Profile("postgres")
public class PostgresProfileConfig {

    @Bean
    public PostgresTicketUnitDao postgresTicketUnitDao(JdbcTemplate jdbcTemplate, SqlCipher sqlCipher) {
        return new PostgresTicketUnitDao(jdbcTemplate, sqlCipher);
    }

    @Bean
    public TicketDao ticketDao(JdbcTemplate jdbcTemplate, SqlCipher sqlCipher) {
        return new PostgresTicketDao(jdbcTemplate, sqlCipher);
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
    public PostgresRollbackDao rollbackDao(JdbcTemplate jdbcTemplate, SqlCipher sqlCipher) {
        return new PostgresRollbackDao(jdbcTemplate, sqlCipher);
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
    public FlowNodeDao flowNodeDao(JdbcTemplate jdbcTemplate) {
        return new PostgresFlowNodeDao(jdbcTemplate);
    }

    @Bean
    public CredentialDao credentialDao(JdbcTemplate jdbcTemplate) {
        return new PostgresCredentialDao(jdbcTemplate);
    }

    @Bean
    public TableRemarkDao tableRemarkDao(JdbcTemplate jdbcTemplate) {
        return new PostgresTableRemarkDao(jdbcTemplate);
    }
}
