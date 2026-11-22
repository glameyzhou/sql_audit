package com.sqlaudit.server.config;

import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.dao.AuditDao;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.impl.jdbc.h2.H2ApprovalDao;
import com.sqlaudit.storage.impl.jdbc.h2.H2AuditDao;
import com.sqlaudit.storage.impl.jdbc.h2.H2ExecLockDao;
import com.sqlaudit.storage.impl.jdbc.h2.H2RollbackDao;
import com.sqlaudit.storage.impl.jdbc.h2.H2TicketDao;
import com.sqlaudit.storage.impl.jdbc.h2.H2TicketUnitDao;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 测试方言装配（profile=h2）：JDBC 真 DAO 接管存储 bean（H2 内存库，MODE=PostgreSQL）。
 *
 * <p>与 {@link MysqlProfileConfig} 同构：{@code @Profile("h2")} 注册 6 个 H2* DAO bean，
 * 其余服务 bean（workflow/audit/query/提交编排）在 memory/mysql/h2 下装配完全一致——
 * 存储切换对业务层零感知。</p>
 *
 * <p>启动：{@code SPRING_PROFILES_ACTIVE=h2 java -jar sqlaudit-server.jar}。
 * Flyway 自动迁移已开启——首次启动执行 H2 V1__init.sql 建 23 表（含创世块 + 4 角色种子）。</p>
 */
@Configuration
@Profile("h2")
public class H2ProfileConfig {

    @Bean
    public H2TicketUnitDao h2TicketUnitDao(JdbcTemplate jdbcTemplate) {
        return new H2TicketUnitDao(jdbcTemplate);
    }

    @Bean
    public TicketDao ticketDao(JdbcTemplate jdbcTemplate) {
        return new H2TicketDao(jdbcTemplate);
    }

    @Bean
    public ApprovalDao approvalDao(JdbcTemplate jdbcTemplate) {
        return new H2ApprovalDao(jdbcTemplate);
    }

    @Bean
    public AuditDao auditDao(JdbcTemplate jdbcTemplate) {
        return new H2AuditDao(jdbcTemplate);
    }

    @Bean
    public H2RollbackDao rollbackDao(JdbcTemplate jdbcTemplate) {
        return new H2RollbackDao(jdbcTemplate);
    }

    @Bean
    public H2ExecLockDao execLockDao(JdbcTemplate jdbcTemplate) {
        return new H2ExecLockDao(jdbcTemplate);
    }
}
