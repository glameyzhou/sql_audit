package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.storage.model.Ticket;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryTicketDao} 单测：条件更新（乐观并发）与快照语义（单实例工单）。
 */
class InMemoryTicketDaoTest {

    private final InMemoryTicketDao dao = new InMemoryTicketDao();

    private long insertDraftTicket(long creatorId, long instanceId, LocalDateTime createdAt) {
        Ticket ticket = new Ticket();
        ticket.setTitle("t-" + creatorId);
        ticket.setCreatorId(creatorId);
        ticket.setInstanceId(instanceId);
        ticket.setRiskLevel(RiskLevel.HIGH);
        ticket.setStatus(TicketStatus.DRAFT);
        ticket.setSqlText("UPDATE t_demo SET a=1 WHERE id=1");
        ticket.setTraceId("trace-" + creatorId);
        ticket.setCreatedAt(createdAt);
        ticket.setUpdatedAt(createdAt);
        return dao.insertTicket(ticket);
    }

    @Test
    void insert与findById_字段回读一致() {
        LocalDateTime now = LocalDateTime.now();
        long id = insertDraftTicket(100L, 7L, now);

        Ticket found = dao.findById(id);
        assertNotNull(found);
        assertEquals("t-100", found.getTitle());
        assertEquals(100L, found.getCreatorId());
        assertEquals(7L, found.getInstanceId(), "单实例工单");
        assertEquals(RiskLevel.HIGH, found.getRiskLevel());
        assertEquals(TicketStatus.DRAFT, found.getStatus());
        assertEquals("UPDATE t_demo SET a=1 WHERE id=1", found.getSqlText(), "内存替身不加密，明文往返");
        assertEquals("trace-100", found.getTraceId());
        assertEquals(now, found.getCreatedAt());
    }

    @Test
    void findById_不存在返回null() {
        assertNull(dao.findById(999L));
    }

    @Test
    void updateStatus条件更新_from匹配返回1并刷新updatedAt() {
        long id = insertDraftTicket(100L, 7L, LocalDateTime.now());
        LocalDateTime changedAt = LocalDateTime.now().plusMinutes(5);

        assertEquals(1, dao.updateStatus(id, TicketStatus.DRAFT, TicketStatus.SUBMITTED, changedAt));
        Ticket found = dao.findById(id);
        assertEquals(TicketStatus.SUBMITTED, found.getStatus());
        assertEquals(changedAt, found.getUpdatedAt());
    }

    @Test
    void updateStatus条件更新_from不匹配返回0且状态不变() {
        long id = insertDraftTicket(100L, 7L, LocalDateTime.now());

        // 状态已被并发推进为 submitted，以 draft 为条件更新必然失败（乐观并发）
        assertEquals(1, dao.updateStatus(id, TicketStatus.DRAFT, TicketStatus.SUBMITTED, LocalDateTime.now()));
        assertEquals(0, dao.updateStatus(id, TicketStatus.DRAFT, TicketStatus.AUDITING, LocalDateTime.now()));

        Ticket found = dao.findById(id);
        assertEquals(TicketStatus.SUBMITTED, found.getStatus());
    }

    @Test
    void updateStatus_工单不存在返回0() {
        assertEquals(0, dao.updateStatus(999L, TicketStatus.DRAFT, TicketStatus.SUBMITTED, LocalDateTime.now()));
    }

    @Test
    void updateStatus并发竞争_同一起点只有一个调用方成功() throws InterruptedException {
        long id = insertDraftTicket(100L, 7L, LocalDateTime.now());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    success.addAndGet(dao.updateStatus(id, TicketStatus.DRAFT, TicketStatus.SUBMITTED, LocalDateTime.now()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, success.get(), "并发竞争下同一 fromStatus 只能有一个影响行数 1");
        assertEquals(TicketStatus.SUBMITTED, dao.findById(id).getStatus());
    }

    @Test
    void listByCreator_按创建时间倒序且只含该创建人() {
        LocalDateTime base = LocalDateTime.now();
        insertDraftTicket(100L, 1L, base.minusHours(3));
        insertDraftTicket(200L, 2L, base.minusHours(2));
        insertDraftTicket(100L, 2L, base.minusHours(1));

        List<Ticket> mine = dao.listByCreator(100L);
        assertEquals(2, mine.size());
        assertEquals(1, dao.listByCreator(200L).size());
        // 最新在前
        assertEquals(base.minusHours(1), mine.get(0).getCreatedAt());
        assertEquals(base.minusHours(3), mine.get(1).getCreatedAt());
    }

    @Test
    void listAll_最新在前_listByInstanceIds_按实例集合过滤() {
        LocalDateTime base = LocalDateTime.now();
        insertDraftTicket(100L, 1L, base.minusHours(3));
        insertDraftTicket(200L, 2L, base.minusHours(2));
        insertDraftTicket(100L, 2L, base.minusHours(1));

        assertEquals(3, dao.listAll().size(), "全部工单");
        assertEquals(base.minusHours(1), dao.listAll().get(0).getCreatedAt(), "最新在前");

        List<Ticket> scoped = dao.listByInstanceIds(List.of(1L));
        assertEquals(1, scoped.size());
        assertTrue(scoped.stream().allMatch(t -> t.getInstanceId() == 1L));
        assertTrue(dao.listByInstanceIds(List.of()).isEmpty(), "空集合 = 数据范围为空（非「不过滤」）");
    }

    @Test
    void 分页读取_creator_全量_按实例集合_页码边界与计数() {
        LocalDateTime base = LocalDateTime.now();
        insertDraftTicket(100L, 1L, base.minusHours(4));
        insertDraftTicket(200L, 2L, base.minusHours(3));
        insertDraftTicket(100L, 2L, base.minusHours(2));
        insertDraftTicket(100L, 1L, base.minusHours(1));

        assertEquals(3L, dao.countByCreator(100L));
        assertEquals(0L, dao.countByCreator(999L));
        List<Ticket> minePage1 = dao.listByCreator(100L, 0, 2);
        assertEquals(2, minePage1.size());
        assertEquals(base.minusHours(1), minePage1.get(0).getCreatedAt(), "最新在前");
        List<Ticket> minePage2 = dao.listByCreator(100L, 2, 2);
        assertEquals(1, minePage2.size());
        assertEquals(base.minusHours(4), minePage2.get(0).getCreatedAt(), "尾页取到最早一条");
        assertTrue(dao.listByCreator(100L, 9, 2).isEmpty(), "越界页为空");

        assertEquals(4L, dao.countAll());
        assertEquals(3, dao.listAll(1, 3).size());
        assertEquals(4, dao.listAll(0, -1).size(), "limit<0 取全部");

        assertEquals(2L, dao.countByInstanceIds(List.of(1L)));
        assertEquals(1, dao.listByInstanceIds(List.of(1L), 0, 1).size());
        assertEquals(0L, dao.countByInstanceIds(List.of()));
        assertTrue(dao.listByInstanceIds(List.of(), 0, 10).isEmpty());
        assertEquals(4L, dao.countByInstanceIds(List.of(1L, 2L)), "多实例并集计数");
    }

    @Test
    void listByStatus_仅返回匹配状态() {
        long id = insertDraftTicket(100L, 1L, LocalDateTime.now());
        dao.updateStatus(id, TicketStatus.DRAFT, TicketStatus.SUBMITTED, LocalDateTime.now());
        insertDraftTicket(200L, 1L, LocalDateTime.now());

        assertEquals(1, dao.listByStatus(TicketStatus.SUBMITTED).size());
        assertEquals(1, dao.listByStatus(TicketStatus.DRAFT).size());
        assertEquals(0, dao.listByStatus(TicketStatus.CLOSED).size());
    }

    @Test
    void markNeedsSecondConfirm与updateSecondConfirm() {
        long id = insertDraftTicket(100L, 1L, LocalDateTime.now());
        assertEquals(1, dao.markNeedsSecondConfirm(id, true));
        assertTrue(dao.findById(id).isNeedsSecondConfirm());

        LocalDateTime confirmedAt = LocalDateTime.now().plusMinutes(1);
        assertEquals(1, dao.updateSecondConfirm(id, 30L, confirmedAt));
        Ticket found = dao.findById(id);
        assertEquals(30L, found.getConfirmedBy());
        assertEquals(confirmedAt, found.getConfirmedAt());
        assertEquals(0, dao.updateSecondConfirm(999L, 30L, confirmedAt));
    }

    @Test
    void findById返回快照_修改返回值不影响内部状态() {
        long id = insertDraftTicket(100L, 1L, LocalDateTime.now());

        Ticket snapshot = dao.findById(id);
        snapshot.setStatus(TicketStatus.CLOSED);
        snapshot.setSqlText("tampered");

        Ticket fresh = dao.findById(id);
        assertEquals(TicketStatus.DRAFT, fresh.getStatus());
        assertEquals("UPDATE t_demo SET a=1 WHERE id=1", fresh.getSqlText());
    }
}
