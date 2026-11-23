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
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlApprovalDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlAuditDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlCredentialDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlExecLockDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlFlowNodeDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlFlowRuleDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlInstanceDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlPermissionDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlRoleDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlRollbackDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlRuleDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlTableRemarkDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlTicketDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlTicketUnitDao;
import com.sqlaudit.storage.impl.jdbc.mysql.MySqlUserDao;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MySQL 方言装配（profile=mysql）：JDBC 真 DAO 接管存储 bean。
 *
 * <p>SQL 密文（工单 / 执行语句 / 回滚反向语句）由 JDBC DAO 用 {@link SqlCipher} 透明加解密，
 * 模型里是明文、库里是密文；密钥走环境变量 {@code SQLAUDIT_SQL_KEY}（缺省本地开发密钥）。</p>
 *
 * <p>启动：{@code SPRING_PROFILES_ACTIVE=mysql java -jar sqlaudit-server.jar}（或
 * {@code dev-home} 组）。连接经环境变量覆盖：SQLAUDIT_DB_URL / SQLAUDIT_DB_USER /
 * SQLAUDIT_DB_PASSWORD（默认值指向本机 Docker MySQL，见 application.yml）。Flyway 自动迁移
 * 已开启——首次启动执行 V1__schema.sql + V2__seed.sql。</p>
 */
@Configuration
@Profile("mysql")
public class MysqlProfileConfig {

    @Bean
    public MySqlTicketUnitDao jdbcTicketUnitDao(JdbcTemplate jdbcTemplate, SqlCipher sqlCipher) {
        return new MySqlTicketUnitDao(jdbcTemplate, sqlCipher);
    }

    @Bean
    public TicketDao ticketDao(JdbcTemplate jdbcTemplate, SqlCipher sqlCipher) {
        return new MySqlTicketDao(jdbcTemplate, sqlCipher);
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
    public MySqlRollbackDao rollbackDao(JdbcTemplate jdbcTemplate, SqlCipher sqlCipher) {
        return new MySqlRollbackDao(jdbcTemplate, sqlCipher);
    }

    @Bean
    public MySqlExecLockDao execLockDao(JdbcTemplate jdbcTemplate) {
        return new MySqlExecLockDao(jdbcTemplate);
    }

    @Bean
    public UserDao userDao(JdbcTemplate jdbcTemplate) {
        return new MySqlUserDao(jdbcTemplate);
    }

    @Bean
    public RoleDao roleDao(JdbcTemplate jdbcTemplate) {
        return new MySqlRoleDao(jdbcTemplate);
    }

    @Bean
    public PermissionDao permissionDao(JdbcTemplate jdbcTemplate) {
        return new MySqlPermissionDao(jdbcTemplate);
    }

    @Bean
    public InstanceDao instanceDao(JdbcTemplate jdbcTemplate) {
        return new MySqlInstanceDao(jdbcTemplate);
    }

    @Bean
    public RuleDao ruleDao(JdbcTemplate jdbcTemplate) {
        return new MySqlRuleDao(jdbcTemplate);
    }

    @Bean
    public FlowRuleDao flowRuleDao(JdbcTemplate jdbcTemplate) {
        return new MySqlFlowRuleDao(jdbcTemplate);
    }

    @Bean
    public FlowNodeDao flowNodeDao(JdbcTemplate jdbcTemplate) {
        return new MySqlFlowNodeDao(jdbcTemplate);
    }

    @Bean
    public CredentialDao credentialDao(JdbcTemplate jdbcTemplate) {
        return new MySqlCredentialDao(jdbcTemplate);
    }

    @Bean
    public TableRemarkDao tableRemarkDao(JdbcTemplate jdbcTemplate) {
        return new MySqlTableRemarkDao(jdbcTemplate);
    }
}
