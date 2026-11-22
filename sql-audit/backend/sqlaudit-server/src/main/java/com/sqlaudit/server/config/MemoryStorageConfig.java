package com.sqlaudit.server.config;

import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.dao.AuditDao;
import com.sqlaudit.storage.dao.RollbackDao;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.storage.impl.memory.InMemoryApprovalDao;
import com.sqlaudit.storage.impl.memory.InMemoryAuditDao;
import com.sqlaudit.storage.impl.memory.InMemoryRollbackDao;
import com.sqlaudit.storage.impl.memory.InMemoryTicketDao;
import com.sqlaudit.storage.impl.memory.InMemoryTicketUnitDao;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 内存存储装配（默认 profile，无库可跑）：4 个 DAO 用 fake 实现。
 * 测试 / 快速联调用；生产用 {@link MysqlProfileConfig}，测试方言用 {@link H2ProfileConfig}。
 */
@Configuration
@Profile("!mysql & !h2")
public class MemoryStorageConfig {

    @Bean
    public TicketDao ticketDao() {
        return new InMemoryTicketDao();
    }

    @Bean
    public ApprovalDao approvalDao() {
        return new InMemoryApprovalDao();
    }

    @Bean
    public TicketUnitDao ticketUnitDao() {
        return new InMemoryTicketUnitDao();
    }

    @Bean
    public AuditDao auditDao() {
        return new InMemoryAuditDao();
    }

    @Bean
    public RollbackDao rollbackDao() {
        return new InMemoryRollbackDao();
    }
}
