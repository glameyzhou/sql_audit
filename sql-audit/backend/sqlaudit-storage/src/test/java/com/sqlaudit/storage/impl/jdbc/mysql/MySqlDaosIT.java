package com.sqlaudit.storage.impl.jdbc.mysql;

import com.sqlaudit.common.crypto.SqlCipher;
import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.common.enums.ApproverType;
import com.sqlaudit.common.enums.InstanceScope;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.RollbackCoverage;
import com.sqlaudit.common.enums.RollbackGenerator;
import com.sqlaudit.common.enums.RollbackStatus;
import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.storage.model.Approval;
import com.sqlaudit.storage.model.AuditLog;
import com.sqlaudit.storage.model.ChainHead;
import com.sqlaudit.storage.model.ExecLock;
import com.sqlaudit.storage.model.FlowNode;
import com.sqlaudit.storage.model.FlowRule;
import com.sqlaudit.storage.model.PermissionApi;
import com.sqlaudit.storage.model.Role;
import com.sqlaudit.storage.model.RollbackSql;
import com.sqlaudit.storage.model.RollbackStatement;
import com.sqlaudit.storage.model.TableRemark;
import com.sqlaudit.storage.model.Ticket;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 其余 JDBC DAO 对本地 Docker MySQL（独立测试库 sql_audit_it）的集成测试，语义对照各自内存版：
 * <ul>
 *   <li>{@link MySqlTicketDao}：updateStatus 乐观并发（含并发竞争只有一个成功）、单实例列、
 *       sql_text 加密落库（原字节非明文 + 解密往返）；</li>
 *   <li>{@link MySqlApprovalDao}：新列（flow_node_id / decided_by 分列）、updateResult 条件更新、
 *       uk_approval_assignee 并发唯一约束、transfer 原子性（代理人已在本组整笔回滚）；</li>
 *   <li>{@link MySqlRollbackDao}：唯一键冲突返回 0、markStatementApplied 幂等 0、
 *       reverse_sql / value_params 加密落库；</li>
 *   <li>{@link MySqlExecLockDao}：tryAcquire 二次 false、条件 release、cleanupExpired；</li>
 *   <li>{@link MySqlAuditDao}：FOR UPDATE 行锁下 seq 递增无空洞、链头推进、listBySeqRange
 *       ——事务边界由调用方管理，测试用 TransactionTemplate 包裹验证；</li>
 *   <li>RBAC 数据面：{@link MySqlRoleDao} 三个「整体替换」+ 读回、{@link MySqlPermissionDao}
 *       接口绑定读取、{@link MySqlUserDao} 防自锁两个查询；</li>
 *   <li>审批流配置面：{@link MySqlFlowRuleDao} findByScope 判重与删除连带节点、
 *       {@link MySqlFlowNodeDao} 整体替换；</li>
 *   <li>表备注词典：{@link MySqlTableRemarkDao} upsert 覆盖不改首次写入人、按库隔离、删除。</li>
 * </ul>
 */
@Tag("it")
class MySqlDaosIT {

    private static HikariDataSource ds;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static final SqlCipher CIPHER = new SqlCipher();

    private static MySqlTicketDao ticketDao;
    private static MySqlApprovalDao approvalDao;
    private static MySqlRollbackDao rollbackDao;
    private static MySqlExecLockDao execLockDao;
    private static MySqlAuditDao auditDao;
    private static MySqlRoleDao roleDao;
    private static MySqlPermissionDao permissionDao;
    private static MySqlUserDao userDao;
    private static MySqlFlowRuleDao flowRuleDao;
    private static MySqlFlowNodeDao flowNodeDao;
    private static MySqlTableRemarkDao tableRemarkDao;

    @BeforeAll
    static void initDatabase() throws Exception {
        Assumptions.assumeTrue(MySqlTestSupport.mysqlAvailable(), "本地 MySQL 未启动，跳过集成测试");
        MySqlTestSupport.ensureDatabase();
        ds = MySqlTestSupport.buildDataSource();
        jdbc = new JdbcTemplate(ds);
        MySqlTestSupport.migrate(ds);
        ticketDao = new MySqlTicketDao(jdbc, CIPHER);
        approvalDao = new MySqlApprovalDao(jdbc);
        rollbackDao = new MySqlRollbackDao(jdbc, CIPHER);
        execLockDao = new MySqlExecLockDao(jdbc);
        auditDao = new MySqlAuditDao(jdbc);
        roleDao = new MySqlRoleDao(jdbc);
        permissionDao = new MySqlPermissionDao(jdbc);
        userDao = new MySqlUserDao(jdbc);
        flowRuleDao = new MySqlFlowRuleDao(jdbc);
        flowNodeDao = new MySqlFlowNodeDao(jdbc);
        tableRemarkDao = new MySqlTableRemarkDao(jdbc);
        txTemplate = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @AfterAll
    static void closeDataSource() {
        if (ds != null) {
            ds.close();
        }
    }

    @BeforeEach
    void cleanTables() {
        if (ds != null) {
            MySqlTestSupport.cleanAndSeed(jdbc);
        }
    }

    /** DDL 为 DATETIME(3) 毫秒精度：截断到毫秒，避免纳秒读写往返误差。 */
    private static LocalDateTime nowMillis() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }

    // ------------------------------------------------------------------
    // TicketDao（单实例 + sql_text 加密）
    // ------------------------------------------------------------------

    private long insertTicket(long creatorId, long instanceId, String sqlText, LocalDateTime createdAt) {
        Ticket ticket = new Ticket();
        ticket.setTitle("t-" + creatorId);
        ticket.setCreatorId(creatorId);
        ticket.setInstanceId(instanceId);
        ticket.setRiskLevel(RiskLevel.HIGH);
        ticket.setStatus(TicketStatus.DRAFT);
        ticket.setRollbackSummary("{\"rollbackable\":[1]}");
        ticket.setSqlText(sqlText);
        ticket.setTraceId("trace-" + creatorId);
        ticket.setCreatedAt(createdAt);
        ticket.setUpdatedAt(createdAt);
        return ticketDao.insertTicket(ticket);
    }

    @Test
    void Ticket_insert与findById_单实例与字段回读一致() {
        LocalDateTime now = nowMillis();
        long id = insertTicket(100L, 7L, "UPDATE t_demo SET a=1 WHERE id=1", now);

        Ticket found = ticketDao.findById(id);
        assertNotNull(found);
        assertEquals("t-100", found.getTitle());
        assertEquals(100L, found.getCreatorId());
        assertEquals(7L, found.getInstanceId(), "工单单实例（instance_id 列）");
        assertEquals(RiskLevel.HIGH, found.getRiskLevel());
        assertEquals(TicketStatus.DRAFT, found.getStatus());
        assertEquals("{\"rollbackable\":[1]}", found.getRollbackSummary(), "JSON 文本原样透传");
        assertEquals("UPDATE t_demo SET a=1 WHERE id=1", found.getSqlText(), "解密往返");
        assertEquals("trace-100", found.getTraceId());
        assertFalse(found.isNeedsSecondConfirm());
        assertEquals(now, found.getCreatedAt());
    }

    @Test
    void Ticket_sqlText加密落库_原列字节不含明文_解密往返() {
        LocalDateTime now = nowMillis();
        String plaintext = "UPDATE t_demo SET a=1 WHERE id=1 -- sensitive";
        long id = insertTicket(100L, 7L, plaintext, now);

        // 直接读原始列（绕过 DAO）：LONGBLOB 里必须是密文，不含明文
        byte[] raw = jdbc.queryForObject("SELECT sql_text FROM t_ticket WHERE id=?", byte[].class, id);
        assertNotNull(raw);
        String rawText = new String(raw, StandardCharsets.UTF_8);
        assertFalse(rawText.contains("sensitive"), "库里不得有 SQL 明文");
        assertFalse(rawText.contains("UPDATE t_demo"), "库里不得有 SQL 明文（含子串）");
        // AES-GCM NoPadding：密文长度 = iv(12B) + 明文长度 + tag(16B)
        assertEquals(12 + plaintext.getBytes(StandardCharsets.UTF_8).length + 16, raw.length,
                "密文长度 = iv(12B) + 密文（=明文长度，NoPadding）+ tag(16B)");
        // SqlCipher 解密往返
        assertEquals(plaintext, CIPHER.decrypt(raw));
        // DAO 读出即明文
        assertEquals(plaintext, ticketDao.findById(id).getSqlText());
    }

    @Test
    void Ticket_sqlText为null_落库NULL_读回null() {
        LocalDateTime now = nowMillis();
        long id = insertTicket(100L, 7L, null, now);
        assertNull(ticketDao.findById(id).getSqlText());
        Integer nullCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_ticket WHERE id=? AND sql_text IS NULL", Integer.class, id);
        assertEquals(1, nullCount, "null 透传为列 NULL");
    }

    @Test
    void Ticket_updateStatus乐观并发_from匹配返回1_from不匹配返回0_不存在返回0() {
        LocalDateTime now = nowMillis();
        long id = insertTicket(100L, 7L, "SELECT 1", now);
        LocalDateTime changedAt = now.plusMinutes(5);

        assertEquals(1, ticketDao.updateStatus(id, TicketStatus.DRAFT, TicketStatus.SUBMITTED, changedAt));
        Ticket found = ticketDao.findById(id);
        assertEquals(TicketStatus.SUBMITTED, found.getStatus());
        assertEquals(changedAt, found.getUpdatedAt(), "updated_at 置为 changedAt（approval_ttl 基准）");

        // 状态已被并发推进，以 draft 为条件再更新必然失败（乐观并发）
        assertEquals(0, ticketDao.updateStatus(id, TicketStatus.DRAFT, TicketStatus.AUDITING, now));
        assertEquals(TicketStatus.SUBMITTED, ticketDao.findById(id).getStatus());

        // 工单不存在
        assertEquals(0, ticketDao.updateStatus(999L, TicketStatus.DRAFT, TicketStatus.SUBMITTED, now));
    }

    @Test
    void Ticket_updateStatus并发竞争_同一起点只有一个调用方成功() throws Exception {
        long id = insertTicket(100L, 7L, "SELECT 1", nowMillis());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    success.addAndGet(ticketDao.updateStatus(id, TicketStatus.DRAFT, TicketStatus.SUBMITTED, nowMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(1, success.get(), "并发竞争下同一 fromStatus 只能有一个影响行数 1");
        assertEquals(TicketStatus.SUBMITTED, ticketDao.findById(id).getStatus());
    }

    @Test
    void Ticket_列表三形态_creator_全量_按实例集合() {
        LocalDateTime base = nowMillis();
        insertTicket(100L, 1L, "SELECT 1", base.minusHours(3));
        insertTicket(200L, 2L, "SELECT 2", base.minusHours(2));
        insertTicket(100L, 2L, "SELECT 3", base.minusHours(1));

        List<Ticket> mine = ticketDao.listByCreator(100L);
        assertEquals(2, mine.size());
        assertEquals(base.minusHours(1), mine.get(0).getCreatedAt(), "最新在前");

        assertEquals(3, ticketDao.listAll().size(), "全部工单");

        List<Ticket> scoped = ticketDao.listByInstanceIds(List.of(1L));
        assertEquals(1, scoped.size(), "数据范围过滤按实例集合");
        assertTrue(scoped.stream().allMatch(t -> t.getInstanceId() == 1L));

        assertTrue(ticketDao.listByInstanceIds(List.of()).isEmpty(),
                "空集合 = 数据范围为空，返回空列表而非全部");
    }

    @Test
    void Ticket_分页读取_creator_全量_按实例集合_页码边界与计数() {
        LocalDateTime base = nowMillis();
        insertTicket(100L, 1L, "SELECT 1", base.minusHours(4));
        insertTicket(200L, 2L, "SELECT 2", base.minusHours(3));
        insertTicket(100L, 2L, "SELECT 3", base.minusHours(2));
        insertTicket(100L, 1L, "SELECT 4", base.minusHours(1));

        // 我的工单：creator=100 共 3 条，created_at 倒序分页
        assertEquals(3L, ticketDao.countByCreator(100L));
        List<Ticket> minePage1 = ticketDao.listByCreator(100L, 0, 2);
        assertEquals(2, minePage1.size());
        assertEquals(base.minusHours(1), minePage1.get(0).getCreatedAt(), "最新在前");
        assertEquals(base.minusHours(2), minePage1.get(1).getCreatedAt());
        List<Ticket> minePage2 = ticketDao.listByCreator(100L, 2, 2);
        assertEquals(1, minePage2.size());
        assertEquals(base.minusHours(4), minePage2.get(0).getCreatedAt(), "尾页取到最早一条");
        assertTrue(ticketDao.listByCreator(100L, 9, 2).isEmpty(), "越界页为空");
        assertEquals(0L, ticketDao.countByCreator(999L));

        // 全部工单：4 条
        assertEquals(4L, ticketDao.countAll());
        assertEquals(3, ticketDao.listAll(1, 3).size(), "offset=1 从第二新开始");
        assertEquals(4, ticketDao.listAll(0, -1).size(), "limit<0 取全部");

        // 按实例集合：inst=1 两条、inst=2 两条；空集合 → 空 + 0
        assertEquals(2L, ticketDao.countByInstanceIds(List.of(1L)));
        List<Ticket> scopedPage = ticketDao.listByInstanceIds(List.of(1L), 0, 1);
        assertEquals(1, scopedPage.size());
        assertEquals(base.minusHours(1), scopedPage.get(0).getCreatedAt());
        assertEquals(0L, ticketDao.countByInstanceIds(List.of()));
        assertTrue(ticketDao.listByInstanceIds(List.of(), 0, 10).isEmpty(),
                "空集合分页读取同样返回空（非「不过滤」）");
        assertEquals(4L, ticketDao.countByInstanceIds(List.of(1L, 2L)), "多实例并集计数");
    }

    @Test
    void Ticket_listByStatus与二次确认字段() {
        long id = insertTicket(100L, 1L, "SELECT 1", nowMillis());
        assertEquals(1, ticketDao.markNeedsSecondConfirm(id, true));
        assertTrue(ticketDao.findById(id).isNeedsSecondConfirm());

        LocalDateTime confirmedAt = nowMillis().plusMinutes(1);
        assertEquals(1, ticketDao.updateSecondConfirm(id, 30L, confirmedAt));
        Ticket found = ticketDao.findById(id);
        assertEquals(30L, found.getConfirmedBy());
        assertEquals(confirmedAt, found.getConfirmedAt());

        // 另留一张 DRAFT 工单，验证状态过滤各归各类
        insertTicket(200L, 1L, "SELECT 2", nowMillis());
        ticketDao.updateStatus(id, TicketStatus.DRAFT, TicketStatus.SUBMITTED, nowMillis());
        assertEquals(1, ticketDao.listByStatus(TicketStatus.SUBMITTED).size());
        assertEquals(1, ticketDao.listByStatus(TicketStatus.DRAFT).size());
        assertEquals(0, ticketDao.listByStatus(TicketStatus.CLOSED).size());
    }

    // ------------------------------------------------------------------
    // ApprovalDao（新列 + 唯一约束 + 转交原子性）
    // ------------------------------------------------------------------

    private long insertApproval(long ticketId, int nodeOrder, ApproverType type, String ref,
                                ApprovalMode mode, ApprovalResult result, LocalDateTime createdAt) {
        Approval approval = new Approval();
        approval.setTicketId(ticketId);
        approval.setFlowNodeId(501L);
        approval.setNodeOrder(nodeOrder);
        approval.setMode(mode);
        approval.setApproverType(type);
        approval.setApproverRef(ref);
        approval.setResult(result);
        approval.setCreatedAt(createdAt);
        return approvalDao.insert(approval);
    }

    @Test
    void Approval_insert与listByTicket_按nodeOrder再按id稳定排序() {
        LocalDateTime now = nowMillis();
        long t1 = 1L;
        // 先插 node2 再插 node1（含同 nodeOrder 多行 = 会签组），验证排序稳定
        insertApproval(t1, 2, ApproverType.ROLE, "DBA", ApprovalMode.OR, ApprovalResult.PENDING, now);
        long firstOfNode1 = insertApproval(t1, 1, ApproverType.USER, "10", ApprovalMode.AND, ApprovalResult.PENDING, now);
        long secondOfNode1 = insertApproval(t1, 1, ApproverType.ROLE, "LEAD", ApprovalMode.AND, ApprovalResult.PENDING, now);
        insertApproval(2L, 1, ApproverType.INSTANCE_OWNER, "30", ApprovalMode.OR, ApprovalResult.PENDING, now);

        List<Approval> rows = approvalDao.listByTicket(t1);
        assertEquals(3, rows.size());
        assertEquals(firstOfNode1, rows.get(0).getId());
        assertEquals(secondOfNode1, rows.get(1).getId());
        assertEquals(2, rows.get(2).getNodeOrder());
        assertTrue(rows.stream().allMatch(r -> r.getTicketId() == t1));
        // 指派对象与配置来源列
        assertEquals(501L, rows.get(0).getFlowNodeId(), "flow_node_id 记录生成它的流程节点");
        assertEquals(ApproverType.USER, rows.get(0).getApproverType());
        assertEquals("10", rows.get(0).getApproverRef());
        assertNull(rows.get(0).getDecidedBy(), "未决策时 decided_by 为空（与指派对象分列）");
    }

    @Test
    void Approval_insert并发重复生成_唯一约束返回0不抛异常() throws Exception {
        LocalDateTime now = nowMillis();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger inserted = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    long id = insertApproval(1L, 1, ApproverType.ROLE, "LEAD", ApprovalMode.OR,
                            ApprovalResult.PENDING, now);
                    if (id > 0) {
                        inserted.incrementAndGet();
                    } else {
                        duplicates.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(1, inserted.get(), "uk_approval_assignee 下并发重复生成只插入一行");
        assertEquals(threads - 1, duplicates.get(), "其余调用方拿到 0（视为已被别人生成）");
    }

    @Test
    void Approval_updateResult条件更新_写decidedBy_不覆盖指派对象() {
        LocalDateTime now = nowMillis();
        long id = insertApproval(1L, 1, ApproverType.ROLE, "LEAD", ApprovalMode.AND, ApprovalResult.PENDING, now);
        LocalDateTime decidedAt = now.plusMinutes(10);

        assertEquals(1, approvalDao.updateResult(id, 30L, ApprovalResult.PENDING, ApprovalResult.APPROVED, "同意", decidedAt));
        Approval found = approvalDao.listByTicket(1L).get(0);
        assertEquals(ApprovalResult.APPROVED, found.getResult());
        assertEquals(30L, found.getDecidedBy(), "实际决策人写入 decided_by");
        assertEquals("LEAD", found.getApproverRef(), "指派对象不被覆盖（消除 approver_id 被覆盖的旧语义）");
        assertEquals(ApproverType.ROLE, found.getApproverType());
        assertEquals("同意", found.getComment());
        assertEquals(decidedAt, found.getDecidedAt());

        // 已是 APPROVED：以 PENDING 为条件的更新（无论目标是什么）都失败
        assertEquals(0, approvalDao.updateResult(id, 30L, ApprovalResult.PENDING, ApprovalResult.REJECTED, "改主意", now));
        assertEquals("同意", approvalDao.listByTicket(1L).get(0).getComment(), "comment 不被覆盖");

        // 记录不存在
        assertEquals(0, approvalDao.updateResult(999L, 30L, ApprovalResult.PENDING, ApprovalResult.APPROVED, null, now));
    }

    @Test
    void Approval_listPending_按创建时间排序() {
        LocalDateTime now = nowMillis();
        long older = insertApproval(1L, 1, ApproverType.ROLE, "LEAD", ApprovalMode.AND, ApprovalResult.PENDING, now.minusHours(2));
        long newer = insertApproval(2L, 1, ApproverType.ROLE, "DBA", ApprovalMode.OR, ApprovalResult.PENDING, now.minusMinutes(5));
        insertApproval(3L, 1, ApproverType.ROLE, "LEAD", ApprovalMode.AND, ApprovalResult.APPROVED, now.minusHours(3));

        List<Approval> pending = approvalDao.listPending();
        assertEquals(2, pending.size());
        assertEquals(older, pending.get(0).getId(), "created_at, id 升序");
        assertEquals(newer, pending.get(1).getId());
    }

    @Test
    void Approval_transfer_原子两步_原记录TRANSFERRED留痕_新记录指向代理人() {
        LocalDateTime now = nowMillis();
        long id = insertApproval(1L, 1, ApproverType.ROLE, "LEAD", ApprovalMode.OR, ApprovalResult.PENDING, now);

        long newId = approvalDao.transfer(id, 10L, 20L, now.plusMinutes(1));
        assertNotEquals(0L, newId, "转交成功返回新记录 id");

        List<Approval> rows = approvalDao.listByTicket(1L);
        assertEquals(2, rows.size());
        Approval original = rows.get(0);
        assertEquals(id, original.getId());
        assertEquals(ApprovalResult.TRANSFERRED, original.getResult(), "原记录置 TRANSFERRED 留痕");
        assertEquals(10L, original.getDecidedBy(), "decided_by = 转交人");
        assertEquals("LEAD", original.getApproverRef(), "原指派对象保留");
        Approval delegate = rows.get(1);
        assertEquals(ApproverType.USER, delegate.getApproverType(), "新记录为 USER 型指向代理人");
        assertEquals("20", delegate.getApproverRef());
        assertEquals(ApprovalResult.PENDING, delegate.getResult());
        assertEquals(1, delegate.getNodeOrder(), "代理人落在同一组");
    }

    @Test
    void Approval_transfer_代理人已在本组_整笔回滚原记录保持PENDING() {
        LocalDateTime now = nowMillis();
        long id = insertApproval(1L, 1, ApproverType.ROLE, "LEAD", ApprovalMode.OR, ApprovalResult.PENDING, now);
        // 同组已有 USER 20
        insertApproval(1L, 1, ApproverType.USER, "20", ApprovalMode.OR, ApprovalResult.PENDING, now);

        long newId = approvalDao.transfer(id, 10L, 20L, now.plusMinutes(1));
        assertEquals(0L, newId, "代理人已在本组（撞唯一键）返回 0");

        // 原记录保持 PENDING（事务回滚，TRANSFERRED 未生效）
        List<Approval> rows = approvalDao.listByTicket(1L);
        assertEquals(2, rows.size());
        assertEquals(ApprovalResult.PENDING, rows.get(0).getResult());
        assertNull(rows.get(0).getDecidedBy());
    }

    @Test
    void Approval_transfer_记录不存在或已决策_返回0不做任何写入() {
        LocalDateTime now = nowMillis();
        assertEquals(0L, approvalDao.transfer(999L, 10L, 20L, now), "记录不存在");

        long decided = insertApproval(1L, 1, ApproverType.ROLE, "LEAD", ApprovalMode.OR, ApprovalResult.PENDING, now);
        assertEquals(1, approvalDao.updateResult(decided, 30L, ApprovalResult.PENDING, ApprovalResult.APPROVED, "ok", now));
        assertEquals(0L, approvalDao.transfer(decided, 10L, 20L, now), "已决策的记录不可转交");
        assertEquals(1, approvalDao.listByTicket(1L).size(), "不做任何写入");
    }

    // ------------------------------------------------------------------
    // RollbackDao（反向语句加密）
    // ------------------------------------------------------------------

    private long insertRollback(long unitId) {
        RollbackSql rollbackSql = new RollbackSql();
        rollbackSql.setUnitId(unitId);
        rollbackSql.setStmtCount(2);
        rollbackSql.setTotalBytes(64L);
        rollbackSql.setGenerator(RollbackGenerator.MIRROR);
        rollbackSql.setStatus(RollbackStatus.GENERATED);
        rollbackSql.setCoverage(RollbackCoverage.FULL);
        rollbackSql.setPreimageHash("p".repeat(64));
        rollbackSql.setCreatedAt(nowMillis());
        assertEquals(1, rollbackDao.insertRollbackSql(rollbackSql));
        return rollbackSql.getId();
    }

    private RollbackStatement makeStatement(long rollbackId, int stmtOrder, String reverseSql, String valueParams) {
        RollbackStatement statement = new RollbackStatement();
        statement.setRollbackId(rollbackId);
        statement.setStmtOrder(stmtOrder);
        statement.setReverseSql(reverseSql);
        statement.setValueParams(valueParams);
        return statement;
    }

    @Test
    void Rollback_insertRollbackSql_unitId唯一_重复插入返回0_findByUnitId_findById() {
        insertRollback(100L);
        RollbackSql duplicate = new RollbackSql();
        duplicate.setUnitId(100L);
        duplicate.setGenerator(RollbackGenerator.MIRROR);
        duplicate.setStatus(RollbackStatus.GENERATED);
        duplicate.setCoverage(RollbackCoverage.FULL);
        assertEquals(0, rollbackDao.insertRollbackSql(duplicate), "uk_rollback_unit 冲突应返回 0");

        RollbackSql found = rollbackDao.findByUnitId(100L);
        assertNotNull(found);
        assertEquals(100L, found.getUnitId());
        assertEquals(RollbackStatus.GENERATED, found.getStatus());
        assertEquals(RollbackCoverage.FULL, found.getCoverage());
        assertEquals(RollbackGenerator.MIRROR, found.getGenerator());
        assertEquals(2, found.getStmtCount());
        assertEquals(64L, found.getTotalBytes());
        assertEquals("p".repeat(64), found.getPreimageHash());
        assertEquals(found.getId(), rollbackDao.findById(found.getId()).getId());
        assertNull(rollbackDao.findByUnitId(999L));
        assertEquals(1, rollbackDao.listAll().size());
    }

    @Test
    void Rollback_反向语句加密落库_原列字节不含明文_解密往返() {
        long rollbackId = insertRollback(100L);
        String reverseSql = "UPDATE t_demo SET a=10 WHERE id=1 -- preimage";
        String valueParams = "{\"id\":1,\"a\":10}";
        assertEquals(1, rollbackDao.insertRollbackStatement(
                makeStatement(rollbackId, 1, reverseSql, valueParams)));

        // 原始列（绕过 DAO）不含明文
        byte[] rawSql = jdbc.queryForObject(
                "SELECT reverse_sql FROM t_rollback_statement WHERE rollback_id=? AND stmt_order=1",
                byte[].class, rollbackId);
        byte[] rawParams = jdbc.queryForObject(
                "SELECT value_params FROM t_rollback_statement WHERE rollback_id=? AND stmt_order=1",
                byte[].class, rollbackId);
        assertFalse(new String(rawSql, StandardCharsets.UTF_8).contains("preimage"), "库里不得有反向语句明文");
        assertFalse(new String(rawParams, StandardCharsets.UTF_8).contains("\"id\":1"), "库里不得有旧值参数明文");
        assertEquals(reverseSql, CIPHER.decrypt(rawSql));
        assertEquals(valueParams, CIPHER.decrypt(rawParams));

        // DAO 读出即明文
        RollbackStatement read = rollbackDao.listStatements(rollbackId, 0, 10).get(0);
        assertEquals(reverseSql, read.getReverseSql());
        assertEquals(valueParams, read.getValueParams());
    }

    @Test
    void Rollback_insertRollbackStatement_rollbackId加stmtOrder唯一_valueParams可空() {
        long rollbackId = insertRollback(100L);

        assertEquals(1, rollbackDao.insertRollbackStatement(
                makeStatement(rollbackId, 1, "UPDATE t SET a=1 WHERE id=1", null)));
        RollbackStatement duplicateOrder = makeStatement(rollbackId, 1, "x", null);
        assertEquals(0, rollbackDao.insertRollbackStatement(duplicateOrder), "uk_rollback_stmt_order 冲突应返回 0");

        RollbackStatement read = rollbackDao.listStatements(rollbackId, 0, 10).get(0);
        assertEquals("UPDATE t SET a=1 WHERE id=1", read.getReverseSql());
        assertNull(read.getValueParams(), "value_params 可空（DDL NULL）");
    }

    @Test
    void Rollback_listStatements_按stmtOrder升序_分页正确() {
        long rollbackId = insertRollback(100L);
        // 乱序插入 3 条
        rollbackDao.insertRollbackStatement(makeStatement(rollbackId, 3, "s3", null));
        rollbackDao.insertRollbackStatement(makeStatement(rollbackId, 1, "s1", null));
        rollbackDao.insertRollbackStatement(makeStatement(rollbackId, 2, "s2", null));

        List<RollbackStatement> all = rollbackDao.listStatements(rollbackId, 0, 10);
        assertEquals(3, all.size());
        assertEquals(1, all.get(0).getStmtOrder());
        assertEquals("s1", all.get(0).getReverseSql());
        assertEquals(3, all.get(2).getStmtOrder());

        // 分批流式读取：offset + limit
        List<RollbackStatement> batch = rollbackDao.listStatements(rollbackId, 1, 1);
        assertEquals(1, batch.size());
        assertEquals(2, batch.get(0).getStmtOrder());

        // 其他 rollback 的语句不混入
        long otherId = insertRollback(200L);
        rollbackDao.insertRollbackStatement(makeStatement(otherId, 1, "o1", null));
        assertEquals(3, rollbackDao.listStatements(rollbackId, 0, 10).size());
    }

    @Test
    void Rollback_markStatementApplied_条件更新幂等() {
        long rollbackId = insertRollback(100L);
        RollbackStatement stmt = makeStatement(rollbackId, 1, "s1", null);
        rollbackDao.insertRollbackStatement(stmt);
        long statementId = stmt.getId();

        LocalDateTime appliedAt = nowMillis();
        assertEquals(1, rollbackDao.markStatementApplied(statementId, appliedAt));
        assertEquals(appliedAt, rollbackDao.listStatements(rollbackId, 0, 10).get(0).getAppliedAt());

        // 已 applied 的重复回放返回 0（applied_at IS NULL 条件不满足）
        assertEquals(0, rollbackDao.markStatementApplied(statementId, appliedAt.plusSeconds(1)));
        assertEquals(appliedAt, rollbackDao.listStatements(rollbackId, 0, 10).get(0).getAppliedAt(),
                "重复回放不得覆盖首次回放时间戳");

        // 不存在的语句返回 0
        assertEquals(0, rollbackDao.markStatementApplied(99999L, appliedAt));
    }

    @Test
    void Rollback_updateStatus_更新主记录状态与原因() {
        long rollbackId = insertRollback(100L);
        assertEquals(1, rollbackDao.updateStatus(rollbackId, RollbackStatus.NOT_AVAILABLE, "R_ROLLBACK_UNAVAILABLE"));
        RollbackSql rollbackSql = rollbackDao.findByUnitId(100L);
        assertEquals(RollbackStatus.NOT_AVAILABLE, rollbackSql.getStatus());
        assertEquals("R_ROLLBACK_UNAVAILABLE", rollbackSql.getUnavailableReason(), "列名 unavailable_reason");

        assertEquals(0, rollbackDao.updateStatus(99999L, RollbackStatus.VERIFIED, null));
    }

    @Test
    void Rollback_listAll分页_按id倒序_计数() {
        long r1 = insertRollback(100L);
        long r2 = insertRollback(200L);
        insertRollback(300L);
        insertRollback(400L);

        assertEquals(4L, rollbackDao.countAll());
        List<RollbackSql> page1 = rollbackDao.listAll(0, 3);
        assertEquals(3, page1.size());
        assertTrue(page1.get(0).getId() > page1.get(1).getId(), "id 倒序（最新在前）");
        List<RollbackSql> page2 = rollbackDao.listAll(3, 3);
        assertEquals(1, page2.size());
        assertEquals(r1, page2.get(0).getId(), "最早一条在尾页");
        assertTrue(rollbackDao.listAll(99, 3).isEmpty(), "越界页为空");
        assertEquals(4, rollbackDao.listAll(0, -1).size(), "limit<0 取全部");
        assertTrue(r2 > r1, "自增 id 递增（前提断言）");
    }

    // ------------------------------------------------------------------
    // ExecLockDao（对照 InMemoryExecLockDaoTest；过期锁由 reaper 清理后重抢）
    // ------------------------------------------------------------------

    private ExecLock makeLock(String lockKey, long unitId, String workerId,
                              LocalDateTime acquiredAt, LocalDateTime expiresAt) {
        ExecLock lock = new ExecLock();
        lock.setLockKey(lockKey);
        lock.setUnitId(unitId);
        lock.setWorkerId(workerId);
        lock.setAcquiredAt(acquiredAt);
        lock.setExpiresAt(expiresAt);
        return lock;
    }

    @Test
    void ExecLock_tryAcquire_首次true_二次false_同owner也不行() {
        LocalDateTime now = nowMillis();
        assertTrue(execLockDao.tryAcquire(makeLock("inst:1", 10L, "worker-a", now, now.plusSeconds(30))));

        // 同 key 第二个 unit（不同 owner）：主键冲突，未过期不可剥夺
        assertFalse(execLockDao.tryAcquire(makeLock("inst:1", 11L, "worker-b", now, now.plusSeconds(30))));
        // 同 owner 重入也算剥夺，返回 false
        assertFalse(execLockDao.tryAcquire(makeLock("inst:1", 10L, "worker-a", now.plusSeconds(1), now.plusSeconds(31))));
    }

    @Test
    void ExecLock_release_只删lockKey与unitId都匹配的锁() {
        LocalDateTime now = nowMillis();
        assertTrue(execLockDao.tryAcquire(makeLock("inst:1", 10L, "worker-a", now, now.plusSeconds(30))));

        // unitId 不匹配：别人的锁删不掉
        assertFalse(execLockDao.release("inst:1", 999L));
        // lockKey 不匹配
        assertFalse(execLockDao.release("inst:2", 10L));

        // 匹配：删除成功；已删除再删返回 false
        assertTrue(execLockDao.release("inst:1", 10L));
        assertFalse(execLockDao.release("inst:1", 10L));

        // 删除后可重新抢到
        assertTrue(execLockDao.tryAcquire(makeLock("inst:1", 12L, "worker-c", now, now.plusSeconds(30))));
    }

    @Test
    void ExecLock_cleanupExpired_只清过期的并返回条数_过期锁清理后可重抢() {
        LocalDateTime now = nowMillis();
        execLockDao.tryAcquire(makeLock("inst:1", 10L, "worker-a", now.minusSeconds(60), now.minusSeconds(30)));
        execLockDao.tryAcquire(makeLock("inst:2", 20L, "worker-b", now.minusSeconds(60), now.minusSeconds(30)));
        execLockDao.tryAcquire(makeLock("inst:3", 30L, "worker-c", now, now.plusSeconds(30)));

        assertEquals(2, execLockDao.cleanupExpired(now));

        // 未过期的还在：unit 30 仍持有 inst:3
        assertFalse(execLockDao.tryAcquire(makeLock("inst:3", 31L, "worker-d", now, now.plusSeconds(30))));
        // 过期的已被清走：inst:1 可重新抢到（JDBC 版过期锁经 reaper 清理后恢复）
        assertTrue(execLockDao.tryAcquire(makeLock("inst:1", 11L, "worker-e", now, now.plusSeconds(30))));
    }

    // ------------------------------------------------------------------
    // AuditDao（对照 InMemoryAuditDaoTest；行锁语义用 TransactionTemplate 验证）
    // ------------------------------------------------------------------

    private AuditLog makeLog(long seq, String prevHash, String hash) {
        AuditLog log = new AuditLog();
        log.setSeq(seq);
        log.setActorId(1L);
        log.setActorName("alice");
        log.setActorRole("DBA");
        log.setAction("EXECUTE_UNIT");
        log.setTargetType("unit");
        log.setTargetId("10");
        log.setInstanceId(1L);
        log.setDetail(new byte[] {1, 2, 3});
        log.setResult("SUCCESS");
        log.setClientIp("10.0.0.1");
        log.setUserAgent("sqlaudit-it");
        log.setTraceId("trace-1");
        log.setPrevHash(prevHash);
        log.setHash(hash);
        return log;
    }

    @Test
    void Audit_种子创世链头_lastSeq为0_lastHash是64个0() {
        // 无锁读即可校验创世状态（cleanAndSeed 已重放种子里的创世块）
        ChainHead head = auditDao.peekChainHead();
        assertEquals(0L, head.getLastSeq());
        assertEquals("0".repeat(64), head.getLastHash());
    }

    @Test
    void Audit_append流程_事务内lock读链头_insert_updateChainHead_seq递增无空洞() {
        String genesis = "0".repeat(64);

        // 第一条：prev_hash = 创世 hash（FOR UPDATE 行锁须在事务内）
        txTemplate.executeWithoutResult(status -> {
            ChainHead head1 = auditDao.lockChainHead();
            assertEquals(0L, head1.getLastSeq());
            assertEquals(genesis, head1.getLastHash());

            assertEquals(1, auditDao.insert(makeLog(1L, head1.getLastHash(), "hash-1")));
            assertEquals(1, auditDao.updateChainHead(1L, "hash-1"));
        });

        // 第二条：链头已推进
        txTemplate.executeWithoutResult(status -> {
            ChainHead head2 = auditDao.lockChainHead();
            assertEquals(1L, head2.getLastSeq());
            assertEquals("hash-1", head2.getLastHash());

            assertEquals(1, auditDao.insert(makeLog(2L, head2.getLastHash(), "hash-2")));
            assertEquals(1, auditDao.updateChainHead(2L, "hash-2"));
        });

        ChainHead current = auditDao.peekChainHead();
        assertEquals(2L, current.getLastSeq());
        assertEquals("hash-2", current.getLastHash());

        // seq 递增无空洞 + prev_hash 链衔接 + detail 与 client_ip / user_agent 往返
        List<AuditLog> logs = auditDao.listBySeqRange(1L, 2L);
        assertEquals(2, logs.size());
        assertEquals(1L, logs.get(0).getSeq());
        assertEquals(2L, logs.get(1).getSeq());
        assertEquals(genesis, logs.get(0).getPrevHash());
        assertEquals("hash-1", logs.get(1).getPrevHash());
        assertArrayEquals(new byte[] {1, 2, 3}, logs.get(0).getDetail());
        assertEquals("10.0.0.1", logs.get(0).getClientIp(), "审计链规范字段 client_ip 写入");
        assertEquals("sqlaudit-it", logs.get(0).getUserAgent(), "审计链规范字段 user_agent 写入");
        assertTrue(logs.get(0).getId() != logs.get(1).getId(), "物理主键自增且不覆盖");
    }

    @Test
    void Audit_listBySeqRange_区间边界与排序() {
        // 直接插入（insert 本身不依赖锁，内存版测试同款造数方式）
        for (long seq = 1; seq <= 5; seq++) {
            auditDao.insert(makeLog(seq, "prev-" + seq, "hash-" + seq));
        }
        auditDao.insert(makeLog(7L, "prev-7", "hash-7"));
        auditDao.insert(makeLog(6L, "prev-6", "hash-6"));

        List<AuditLog> range = auditDao.listBySeqRange(2L, 6L);
        assertEquals(5, range.size(), "乱序插入后按 seq 升序返回");
        for (int i = 0; i < range.size(); i++) {
            assertEquals(2L + i, range.get(i).getSeq());
        }

        assertEquals(0, auditDao.listBySeqRange(100L, 200L).size());
    }

    @Test
    void Audit_listBySeqRange分页_页码边界与计数() {
        // 乱序直插 1..6（缺 seq=5 之外无空洞；分页语义与插入顺序无关）
        for (long seq = 1; seq <= 4; seq++) {
            auditDao.insert(makeLog(seq, "prev-" + seq, "hash-" + seq));
        }
        auditDao.insert(makeLog(6L, "prev-6", "hash-6"));
        auditDao.insert(makeLog(5L, "prev-5", "hash-5"));

        assertEquals(6L, auditDao.countBySeqRange(1L, 6L));
        assertEquals(2L, auditDao.countBySeqRange(2L, 3L), "区间计数按边界收窄");

        List<AuditLog> page1 = auditDao.listBySeqRange(1L, 6L, 0, 2);
        assertEquals(2, page1.size());
        assertEquals(1L, page1.get(0).getSeq());
        assertEquals(2L, page1.get(1).getSeq());
        List<AuditLog> page3 = auditDao.listBySeqRange(1L, 6L, 4, 2);
        assertEquals(2, page3.size());
        assertEquals(5L, page3.get(0).getSeq(), "offset 越过缺号区间后仍按 seq 升序");
        assertEquals(6L, page3.get(1).getSeq());
        assertTrue(auditDao.listBySeqRange(1L, 6L, 99, 2).isEmpty(), "越界页为空");
        assertEquals(6, auditDao.listBySeqRange(1L, 6L, 0, -1).size(), "limit<0 取全部");
        assertEquals(0L, auditDao.countBySeqRange(100L, 200L));
    }

    @Test
    void Audit_并发append_FOR_UPDATE行锁串行_seq连续无空洞() throws Exception {
        int threads = 6;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    // 单条 append 流程：lock → 算 seq/hash → insert → updateChainHead，全程一个事务
                    txTemplate.executeWithoutResult(status -> {
                        ChainHead head = auditDao.lockChainHead();
                        long seq = head.getLastSeq() + 1;
                        String hash = "hash-" + seq;
                        assertEquals(1, auditDao.insert(makeLog(seq, head.getLastHash(), hash)));
                        assertEquals(1, auditDao.updateChainHead(seq, hash));
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发 append 应全部完成");
        pool.shutdownNow();

        // 行锁串行 → seq 连续无空洞，hash 链两两衔接
        assertEquals((long) threads, auditDao.peekChainHead().getLastSeq());
        List<AuditLog> logs = auditDao.listBySeqRange(1L, threads);
        assertEquals(threads, logs.size());
        assertEquals("0".repeat(64), logs.get(0).getPrevHash());
        for (int i = 0; i < logs.size(); i++) {
            assertEquals(1L + i, logs.get(i).getSeq(), "seq 连续无空洞");
            if (i > 0) {
                assertEquals(logs.get(i - 1).getHash(), logs.get(i).getPrevHash(), "prev_hash 链衔接");
            }
        }
    }

    // ------------------------------------------------------------------
    // RoleDao：整体替换 + 读回（RBAC 数据面）
    // ------------------------------------------------------------------

    @Test
    void Role_种子角色读回_经code查_整体替换权限与实例授权() {
        // 种子：5 个内置角色，SUPER_ADMIN 持有全部权限点
        Role superAdmin = roleDao.findByCode("SUPER_ADMIN");
        assertNotNull(superAdmin);
        assertTrue(superAdmin.isBuiltin(), "内置角色 builtin=TRUE");
        assertEquals(InstanceScope.ALL, superAdmin.getInstanceScope());

        Role dev = roleDao.findByCode("DEV");
        List<Long> devPermissions = roleDao.listPermissionIds(dev.getId());
        assertFalse(devPermissions.isEmpty(), "种子 DEV 授权非空");
        List<Long> devInstances = roleDao.listInstanceIds(dev.getId());
        assertTrue(devInstances.isEmpty(), "ALL 数据范围角色不写实例授权行");

        // 建一个自定义角色 + 两个权限点 + 一个实例，再整体替换读回
        Role custom = new Role();
        custom.setCode("TEAM_X");
        custom.setName("团队 X");
        custom.setDescription("测试自定义角色");
        custom.setBuiltin(false);
        custom.setInstanceScope(InstanceScope.SELECTED);
        long roleId = roleDao.insert(custom);
        assertTrue(roleId > 0);

        List<Long> permissionIds = jdbc.queryForList(
                "SELECT id FROM t_permission WHERE code IN ('ticket.submit','query.run') ORDER BY id", Long.class);
        Long devId = jdbc.queryForObject("SELECT id FROM t_user WHERE username='dev'", Long.class);
        roleDao.replacePermissions(roleId, permissionIds, 1L);
        roleDao.replaceInstances(roleId, List.of(7L, 8L), 1L);
        roleDao.replaceUserRoles(devId, List.of(roleId), 1L);

        assertEquals(permissionIds, roleDao.listPermissionIds(roleId), "权限授权整体替换后按 id 升序读回");
        assertEquals(List.of(7L, 8L), roleDao.listInstanceIds(roleId), "实例授权整体替换后读回");
        assertEquals(List.of("TEAM_X"),
                roleDao.listByUserId(devId).stream().map(Role::getCode).toList(), "用户角色替换后读回");
        assertEquals(1, roleDao.countUsers(roleId));
        assertTrue(roleDao.hasActiveMember(roleId, null), "dev 是 ACTIVE 成员");
        assertFalse(roleDao.hasActiveMember(roleId, devId), "排除 dev 后该角色无人（审批流节点保存的判空依据）");

        // 再替换为空 = 清空
        roleDao.replaceInstances(roleId, List.of(), null);
        assertTrue(roleDao.listInstanceIds(roleId).isEmpty(), "空集合清空授权");

        // update：name / description / instance_scope 可改，code / builtin 不动
        custom.setId(roleId);
        custom.setName("团队 X2");
        custom.setDescription("改名后");
        assertEquals(1, roleDao.update(custom));
        Role reloaded = roleDao.findById(roleId);
        assertEquals("TEAM_X", reloaded.getCode(), "code 不被 update 覆盖");
        assertEquals("团队 X2", reloaded.getName());
        assertFalse(reloaded.isBuiltin());
        assertNotNull(reloaded.getUpdatedAt());

        // delete：连带用户关联 / 权限授权 / 实例授权
        assertEquals(1, roleDao.delete(roleId));
        assertNull(roleDao.findById(roleId));
        assertTrue(roleDao.listByUserId(devId).isEmpty());
        assertEquals(0, roleDao.countUsers(roleId));
    }

    @Test
    void Role_内置角色替换权限后防自锁计数可见() {
        // 种子 SUPER_ADMIN 持有全部权限点（含 meta）→ countActiveMetaPermissionHolders >= 1
        assertTrue(userDao.countActiveMetaPermissionHolders() >= 1,
                "种子状态必须满足防自锁不变式（admin 经 SUPER_ADMIN 持有 rbac.manage）");
        // lockMetaPermissions 只锁元权限行（事务外调用仅作冒烟，语义测试在事务内才有意义）
        List<Long> locked = userDao.lockMetaPermissions();
        assertEquals(1, locked.size(), "只有 rbac.manage 一个元权限点");
        Integer metaCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_permission WHERE meta = TRUE", Integer.class);
        assertEquals(metaCount, locked.size());
    }

    // ------------------------------------------------------------------
    // PermissionDao：目录 + 接口绑定（只读数据面）
    // ------------------------------------------------------------------

    @Test
    void Permission_目录与接口绑定读回_角色授权对() {
        var permissions = permissionDao.listAll();
        assertTrue(permissions.size() >= 14, "权限点目录非空（basic + 13 业务权限点）");
        assertTrue(permissions.get(0).isAllUsers(), "basic 是 all_users");
        assertTrue(permissions.stream().anyMatch(p -> p.isMeta()), "rbac.manage 是元权限");

        var basic = permissionDao.findByCode("basic");
        assertNotNull(basic);
        List<PermissionApi> basicApis = permissionDao.listApisByPermission(basic.getId());
        assertTrue(basicApis.stream().anyMatch(a ->
                        "GET".equals(a.getHttpMethod()) && "/api/tickets".equals(a.getUrlPattern())),
                "接口绑定按「方法 + 处理器模式」原文存储");
        assertTrue(permissionDao.listAllApis().size() > basicApis.size(), "全部绑定 > 单权限点绑定");

        // 角色授权对：SUPER_ADMIN → 全部业务权限点
        // （basic 为 all_users，种子按契约 §2.4 不写授权行——它对所有人默认放行，
        //  所以「角色持有」的断言对象是非 all_users 的权限点）
        Role superAdmin = roleDao.findByCode("SUPER_ADMIN");
        List<long[]> pairs = permissionDao.listRolePermissionPairs();
        assertFalse(pairs.isEmpty());
        long held = pairs.stream().filter(pair -> pair[0] == superAdmin.getId()).count();
        long nonAllUsers = permissions.stream().filter(p -> !p.isAllUsers()).count();
        assertEquals(nonAllUsers, held, "SUPER_ADMIN 经角色持有全部非 all_users 权限点");
        var queryRun = permissionDao.findByCode("query.run");
        assertNotNull(queryRun);
        assertTrue(permissionDao.listRoleIdsByPermission(queryRun.getId()).stream()
                        .anyMatch(id -> id.longValue() == superAdmin.getId().longValue()),
                "listRoleIdsByPermission 反向查角色（非 all_users 权限点）");
    }

    // ------------------------------------------------------------------
    // FlowRuleDao + FlowNodeDao：范围判重、节点整体替换、删除连带
    // ------------------------------------------------------------------

    @Test
    void Flow_种子全局流程_findByScope判重_update不改范围_删除连带节点() {
        FlowRule medium = flowRuleDao.findByScope(null, RiskLevel.MEDIUM);
        assertNotNull(medium, "种子全局 MEDIUM 流程");
        assertNull(medium.getInstanceId());
        assertTrue(medium.getEnabled());
        assertNotNull(medium.getUpdatedAt());
        assertEquals(List.of("LEAD"), flowNodeDao.listByFlowRule(medium.getId())
                .stream().map(FlowNode::getApproverRef).toList(), "种子 MEDIUM 节点 = LEAD");

        // 全量加载：4 条全局流程 × 各自节点
        assertEquals(4, flowRuleDao.listAll().size());
        assertTrue(flowNodeDao.listAll().size() >= 6, "种子节点 LEAD/LEAD+DBA/LEAD+DBA+ADMIN 共 6 条");

        // findByScope 对实例范围与全局范围互不混淆
        assertNull(flowRuleDao.findByScope(9L, RiskLevel.MEDIUM), "实例专用流程尚不存在");

        // update 只改 name / enabled
        medium.setName("全局 · MEDIUM v2");
        medium.setEnabled(false);
        assertEquals(1, flowRuleDao.update(medium));
        FlowRule reloaded = flowRuleDao.findById(medium.getId());
        assertEquals("全局 · MEDIUM v2", reloaded.getName());
        assertFalse(reloaded.getEnabled());
        assertNull(reloaded.getInstanceId(), "范围不被 update 覆盖");
        assertEquals(RiskLevel.MEDIUM, reloaded.getRiskLevel(), "风险等级不被 update 覆盖");

        // 新建实例专用流程 + 整体替换节点 + 删除连带
        FlowRule instanceFlow = new FlowRule();
        instanceFlow.setName("实例 9 · HIGH");
        instanceFlow.setInstanceId(9L);
        instanceFlow.setRiskLevel(RiskLevel.HIGH);
        instanceFlow.setEnabled(true);
        long flowId = flowRuleDao.insert(instanceFlow);
        assertTrue(flowId > 0);
        assertNotNull(flowRuleDao.findByScope(9L, RiskLevel.HIGH));

        FlowNode n1 = new FlowNode();
        n1.setNodeOrder(1);
        n1.setApproverType(ApproverType.USER);
        n1.setApproverRef("10");
        n1.setMode(ApprovalMode.AND);
        FlowNode n2 = new FlowNode();
        n2.setNodeOrder(1);
        n2.setApproverType(ApproverType.USER);
        n2.setApproverRef("20");
        n2.setMode(ApprovalMode.AND);
        FlowNode n3 = new FlowNode();
        n3.setNodeOrder(2);
        n3.setApproverType(ApproverType.INSTANCE_OWNER);
        n3.setApproverRef("*");
        n3.setMode(ApprovalMode.OR);
        assertEquals(3, flowNodeDao.replaceNodes(flowId, List.of(n1, n2, n3)));

        List<FlowNode> nodes = flowNodeDao.listByFlowRule(flowId);
        assertEquals(3, nodes.size());
        assertEquals(flowId, nodes.get(0).getFlowRuleId(), "flowRuleId 由 replaceNodes 统一赋值");
        assertEquals(List.of("10", "20", "*"), nodes.stream().map(FlowNode::getApproverRef).toList());
        assertEquals(2, nodes.get(2).getNodeOrder());

        // 再整体替换 = 旧节点全删
        FlowNode n4 = new FlowNode();
        n4.setNodeOrder(1);
        n4.setApproverType(ApproverType.ROLE);
        n4.setApproverRef("DBA");
        n4.setMode(ApprovalMode.OR);
        assertEquals(1, flowNodeDao.replaceNodes(flowId, List.of(n4)));
        assertEquals(List.of("DBA"), flowNodeDao.listByFlowRule(flowId)
                .stream().map(FlowNode::getApproverRef).toList());

        // 删流程连带节点（单事务）
        assertEquals(1, flowRuleDao.delete(flowId));
        assertNull(flowRuleDao.findById(flowId));
        assertTrue(flowNodeDao.listByFlowRule(flowId).isEmpty(), "删除流程连带节点");
        // deleteByFlowRule 也可单独调用
        assertEquals(1, flowNodeDao.deleteByFlowRule(medium.getId()));
        assertTrue(flowNodeDao.listByFlowRule(medium.getId()).isEmpty());
    }

    @Test
    void Flow_实例专用流程唯一约束_库级兜底() {
        FlowRule flow = new FlowRule();
        flow.setName("实例 5 · LOW");
        flow.setInstanceId(5L);
        flow.setRiskLevel(RiskLevel.LOW);
        flow.setEnabled(true);
        assertTrue(flowRuleDao.insert(flow) > 0);

        FlowRule duplicate = new FlowRule();
        duplicate.setName("实例 5 · LOW 重复");
        duplicate.setInstanceId(5L);
        duplicate.setRiskLevel(RiskLevel.LOW);
        duplicate.setEnabled(true);
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> flowRuleDao.insert(duplicate),
                "uk_flow_scope_risk(instance_id, risk_level) 库级唯一约束");
    }

    // ------------------------------------------------------------------
    // TableRemarkDao（表备注词典）
    // ------------------------------------------------------------------

    @Test
    void TableRemark_upsert覆盖与查询_createdBy保持首次写入人() {
        Long adminId = jdbc.queryForObject("SELECT id FROM t_user WHERE username='admin'", Long.class);
        Long devId = jdbc.queryForObject("SELECT id FROM t_user WHERE username='dev'", Long.class);

        TableRemark r = new TableRemark();
        r.setInstanceId(9L);
        r.setSchemaName("sql_audit_demo");
        r.setTableName("t_demo");
        r.setRemark("订单主表");
        r.setCreatedBy(adminId);
        assertEquals(1, tableRemarkDao.upsert(r), "首次写入影响 1 行");

        List<TableRemark> list = tableRemarkDao.findByInstanceAndSchema(9L, "sql_audit_demo");
        assertEquals(1, list.size());
        assertEquals("t_demo", list.get(0).getTableName());
        assertEquals("订单主表", list.get(0).getRemark());
        assertEquals(adminId, list.get(0).getCreatedBy());
        assertNotNull(list.get(0).getCreatedAt());

        // 覆盖：remark 更新、created_by 保持首次写入人
        TableRemark update = new TableRemark();
        update.setInstanceId(9L);
        update.setSchemaName("sql_audit_demo");
        update.setTableName("t_demo");
        update.setRemark("订单主表（改）");
        update.setCreatedBy(devId);
        assertEquals(2, tableRemarkDao.upsert(update), "MySQL ON DUPLICATE KEY UPDATE 影响 2 行");
        List<TableRemark> after = tableRemarkDao.findByInstanceAndSchema(9L, "sql_audit_demo");
        assertEquals(1, after.size());
        assertEquals("订单主表（改）", after.get(0).getRemark());
        assertEquals(adminId, after.get(0).getCreatedBy(), "覆盖不改首次写入人");

        // 按库隔离 + 删除（清空备注语义）
        assertTrue(tableRemarkDao.findByInstanceAndSchema(9L, "other_db").isEmpty());
        assertEquals(1, tableRemarkDao.delete(9L, "sql_audit_demo", "t_demo"));
        assertEquals(0, tableRemarkDao.delete(9L, "sql_audit_demo", "t_demo"), "再删 0 行");
        assertTrue(tableRemarkDao.findByInstanceAndSchema(9L, "sql_audit_demo").isEmpty());
    }

    @Test
    void TableRemark_唯一键为实例加库加表_同表不同实例可并存() {
        TableRemark a = new TableRemark();
        a.setInstanceId(1L);
        a.setSchemaName("db");
        a.setTableName("t");
        a.setRemark("实例一备注");
        a.setCreatedBy(1L);
        TableRemark b = new TableRemark();
        b.setInstanceId(2L);
        b.setSchemaName("db");
        b.setTableName("t");
        b.setRemark("实例二备注");
        b.setCreatedBy(1L);
        tableRemarkDao.upsert(a);
        tableRemarkDao.upsert(b);
        assertEquals(1, tableRemarkDao.findByInstanceAndSchema(1L, "db").size());
        assertEquals(1, tableRemarkDao.findByInstanceAndSchema(2L, "db").size());
    }
}
