package com.sqlaudit.worker.gateway;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.common.enums.ApproverType;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.GateCode;
import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.executor.gate.ExecutionGate;
import com.sqlaudit.parser.SqlParserService;
import com.sqlaudit.rollback.capability.RollbackCapabilityAssessor;
import com.sqlaudit.rule.TableMetadataProvider;
import com.sqlaudit.rule.engine.RuleEngine;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.impl.memory.InMemoryApprovalDao;
import com.sqlaudit.storage.impl.memory.InMemoryTicketDao;
import com.sqlaudit.storage.model.Approval;
import com.sqlaudit.storage.model.Instance;
import com.sqlaudit.storage.model.Ticket;
import com.sqlaudit.storage.model.TicketUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkerGateContext} 单测：
 * approvedAt 取最新 APPROVED decidedAt；回滚能力复核两分支；onGateFailure TTL 补偿。
 */
class WorkerGateContextTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 11, 22, 10, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 11, 22, 11, 0, 0);

    private final InMemoryApprovalDao approvalDao = new InMemoryApprovalDao();
    private final InMemoryTicketDao ticketDao = new InMemoryTicketDao();
    /** 实例内存替身（storage 的 InMemoryInstanceDao 已随 memory profile 删除）。 */
    private final InstanceDao instanceDao = new FakeInstanceDao();
    private final WorkerGateContext ctx;

    WorkerGateContextTest() {
        TableMetadataProvider metadata = metadata();
        ctx = new WorkerGateContext(approvalDao, ticketDao, instanceDao,
                new SqlParserService(), new RuleEngine(metadata),
                new RollbackCapabilityAssessor(), metadata);
    }

    @BeforeEach
    void insertMySqlInstance() {
        Instance inst = new Instance();
        inst.setName("ctx-test");
        inst.setDialect(Dialect.MYSQL);
        inst.setHost("127.0.0.1");
        inst.setPort(3306);
        instanceDao.insert(inst);
    }

    // ------------------------------------------------------------------
    // approvedAt
    // ------------------------------------------------------------------

    @Test
    void approvedAt_picksLatestApprovedDecidedAt() {
        TicketUnit unit = unit(1L, 1L);
        approvalDao.insert(approval(1L, 1, "7", ApprovalResult.APPROVED, T1));
        approvalDao.insert(approval(1L, 2, "8", ApprovalResult.PENDING, null));
        approvalDao.insert(approval(1L, 3, "9", ApprovalResult.APPROVED, T2));

        assertEquals(T2, ctx.approvedAt(unit), "应取 APPROVED 记录里最新的 decidedAt");
    }

    @Test
    void approvedAt_noApprovedRecord_returnsNull() {
        TicketUnit unit = unit(1L, 1L);
        approvalDao.insert(approval(1L, 1, "7", ApprovalResult.PENDING, null));
        approvalDao.insert(approval(1L, 2, "8", ApprovalResult.REJECTED, T1));

        assertNull(ctx.approvedAt(unit), "无 APPROVED 记录应返回 null");
    }

    // ------------------------------------------------------------------
    // rollbackStillAvailable
    // ------------------------------------------------------------------

    @Test
    void rollbackStillAvailable_updateWithPk_returnsTrue() {
        TicketUnit unit = unit(1L, 1L);
        unit.setStatement("UPDATE t_demo SET a = 99 WHERE id = 1");

        assertTrue(ctx.rollbackStillAvailable(unit), "有主键确定性 UPDATE 应判可回滚");
    }

    @Test
    void rollbackStillAvailable_noPkTarget_returnsFalse() {
        TicketUnit unit = unit(1L, 1L);
        unit.setStatement("UPDATE t_nopk SET a = 1 WHERE id = 1");

        assertFalse(ctx.rollbackStillAvailable(unit), "无主键表（R_NO_PK）应判回滚能力缺失");
    }

    @Test
    void rollbackStillAvailable_instanceMissing_returnsFalseSafetySide() {
        TicketUnit unit = unit(1L, 999_999L);
        unit.setStatement("UPDATE t_demo SET a = 99 WHERE id = 1");

        assertFalse(ctx.rollbackStillAvailable(unit), "实例缺失（复核异常）应安全侧阻断");
    }

    // ------------------------------------------------------------------
    // onGateFailure
    // ------------------------------------------------------------------

    @Test
    void onGateFailure_approvalTtl_marksTicketExpired() {
        Ticket ticket = new Ticket();
        ticket.setStatus(TicketStatus.APPROVED);
        long ticketId = ticketDao.insertTicket(ticket);

        ctx.onGateFailure(unit(ticketId, 1L),
                ExecutionGate.GateResult.fail(GateCode.APPROVAL_TTL, "审批已超 24h 有效期"));

        assertEquals(TicketStatus.EXPIRED, ticketDao.findById(ticketId).getStatus(),
                "README §4.2：审批超时置 expired，需重新审批");
    }

    @Test
    void onGateFailure_otherGate_leavesTicketStatus() {
        Ticket ticket = new Ticket();
        ticket.setStatus(TicketStatus.APPROVED);
        long ticketId = ticketDao.insertTicket(ticket);

        ctx.onGateFailure(unit(ticketId, 1L),
                ExecutionGate.GateResult.fail(GateCode.SCHEMA_DRIFT, "表结构漂移"));

        assertEquals(TicketStatus.APPROVED, ticketDao.findById(ticketId).getStatus(),
                "非 TTL 门禁不做 expired 补偿（退回重审走 unit failed 路径）");
    }

    // ------------------------------------------------------------------
    // 基建
    // ------------------------------------------------------------------

    private static TicketUnit unit(long ticketId, long instanceId) {
        TicketUnit u = new TicketUnit();
        u.setId(1L);
        u.setTicketId(ticketId);
        u.setInstanceId(instanceId);
        return u;
    }

    private static Approval approval(long ticketId, int nodeOrder, String ref,
                                     ApprovalResult result, LocalDateTime decidedAt) {
        Approval a = new Approval();
        a.setTicketId(ticketId);
        a.setNodeOrder(nodeOrder);
        a.setApproverType(ApproverType.USER);
        a.setApproverRef(ref);
        a.setMode(ApprovalMode.OR);
        a.setResult(result);
        a.setDecidedAt(decidedAt);
        a.setCreatedAt(decidedAt);
        return a;
    }

    /** t_demo 有主键（可回滚），其余表无主键（R_NO_PK → 回滚能力缺失）。 */
    private static TableMetadataProvider metadata() {
        return new TableMetadataProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public TableMetadata getTable(String schema, String name) {
                if ("t_demo".equals(name)) {
                    return new TableMetadata(true, List.of("id"), Set.of(),
                            List.of("id", "a"), Set.of(), true);
                }
                return new TableMetadata(false, List.of(), Set.of(),
                        List.of("a"), Set.of(), true);
            }
        };
    }

    /** 只读最小实例替身（insert 回填 id、findById 查询）。 */
    private static final class FakeInstanceDao implements InstanceDao {
        private final Map<Long, Instance> store = new LinkedHashMap<>();
        private long seq = 0;

        @Override
        public List<Instance> listAll() {
            return List.copyOf(store.values());
        }

        @Override
        public Instance findById(long id) {
            return store.get(id);
        }

        @Override
        public Instance findByName(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long insert(Instance instance) {
            instance.setId(++seq);
            store.put(instance.getId(), instance);
            return instance.getId();
        }

        @Override
        public int update(Instance instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int delete(long id) {
            throw new UnsupportedOperationException();
        }
    }
}
