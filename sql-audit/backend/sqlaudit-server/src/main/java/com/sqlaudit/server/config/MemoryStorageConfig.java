package com.sqlaudit.server.config;

import com.sqlaudit.executor.gate.ExecutionGate;
import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.dao.AuditDao;
import com.sqlaudit.storage.dao.CredentialDao;
import com.sqlaudit.storage.dao.FlowRuleDao;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.dao.RollbackDao;
import com.sqlaudit.storage.dao.RuleDao;
import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.dao.TicketUnitDao;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.impl.memory.InMemoryApprovalDao;
import com.sqlaudit.storage.impl.memory.InMemoryAuditDao;
import com.sqlaudit.storage.impl.memory.InMemoryCredentialDao;
import com.sqlaudit.storage.impl.memory.InMemoryFlowRuleDao;
import com.sqlaudit.storage.impl.memory.InMemoryInstanceDao;
import com.sqlaudit.storage.impl.memory.InMemoryRoleDao;
import com.sqlaudit.storage.impl.memory.InMemoryRollbackDao;
import com.sqlaudit.storage.impl.memory.InMemoryRuleDao;
import com.sqlaudit.storage.impl.memory.InMemoryTicketDao;
import com.sqlaudit.storage.impl.memory.InMemoryTicketUnitDao;
import com.sqlaudit.storage.impl.memory.InMemoryUserDao;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Formatter;

/**
 * 内存存储装配（默认 profile，无库可跑）：4 个 DAO 用 fake 实现。
 * 测试 / 快速联调用；生产用 {@link MysqlProfileConfig}，PG 用 {@link PostgresProfileConfig}，
 * 测试方言用 {@link H2ProfileConfig}。
 */
@Configuration
@Profile("!mysql & !postgres & !h2")
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

    @Bean
    public UserDao userDao() {
        return new InMemoryUserDao();
    }

    @Bean
    public RoleDao roleDao() {
        return new InMemoryRoleDao();
    }

    @Bean
    public InstanceDao instanceDao() {
        return new InMemoryInstanceDao();
    }

    @Bean
    public RuleDao ruleDao() {
        return new InMemoryRuleDao();
    }

    @Bean
    public FlowRuleDao flowRuleDao() {
        return new InMemoryFlowRuleDao();
    }

    @Bean
    public CredentialDao credentialDao() {
        return new InMemoryCredentialDao();
    }

    /**
     * 表结构指纹提供者（设计文档 §3.5）——memory 专属 fake：返回
     * {@code SHA-256("stub:" + schema + "." + table)}，确定性可断言，让 memory profile
     * 下审批采集与联调测试可跑。
     *
     * <p><strong>与 worker 侧同 bean 名互斥</strong>：worker 的 Jdbc 真实现只在
     * mysql/postgres/h2 profile 注册（{@code WorkerConfig}），本类仅在
     * {@code !mysql & !postgres & !h2} 生效，两者不会同框冲突。</p>
     */
    @Bean
    public ExecutionGate.StructureFingerprintProvider structureFingerprintProvider() {
        return (instanceId, schema, table) -> sha256("stub:" + schema + "." + table);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (Formatter f = new Formatter()) {
                for (byte b : md.digest(s.getBytes(StandardCharsets.UTF_8))) {
                    f.format("%02x", b);
                }
                return f.toString();
            }
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
