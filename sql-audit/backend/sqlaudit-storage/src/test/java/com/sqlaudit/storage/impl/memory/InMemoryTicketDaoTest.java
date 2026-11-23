package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.common.enums.Environment;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.TicketStatus;
import com.sqlaudit.storage.model.Ticket;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * {@link InMemoryTicketDao} 单测：覆盖 B-3 验收要求的条件更新（乐观并发）与快照语义。
 */
class InMemoryTicketDaoTest {

    private final InMemoryTicketDao dao = new InMemoryTicketDao();

    private long insertDraftTicket(long creatorId, LocalDateTime createdAt) {
        Ticket ticket = new Ticket();
        ticket.setTitle("t-" + creatorId);
        ticket.setCreatorId(creatorId);
        ticket.setInstanceIds(new ArrayList<>(List.of(1L)));
        ticket.setEnvironment(Environment.PROD);
        ticket.setRiskLevel(RiskLevel.HIGH);
        ticket.setStatus(TicketStatus.DRAFT);
        ticket.setTraceId("trace-" + creatorId);
        ticket.setCreatedAt(createdAt);
        ticket.setUpdatedAt(createdAt);
        return dao.insertTicket(ticket);
    }

    @Test
    void insert与findById_字段回读一致() {
        LocalDateTime now = LocalDateTime.now();
        long id = insertDraftTicket(100L, now);

        Ticket found = dao.findById(id);
        assertNotNull(found);
        assertEquals("t-100", found.getTitle());
        assertEquals(100L, found.getCreatorId());
        assertEquals(List.of(1L), found.getInstanceIds());
        assertEquals(RiskLevel.HIGH, found.getRiskLevel());
        assertEquals(TicketStatus.DRAFT, found.getStatus());
        assertEquals("trace-100", found.getTraceId());
        assertEquals(now, found.getCreatedAt());
    }

    @Test
    void findById_不存在返回null() {
        assertNull(dao.findById(999L));
    }

    @Test
    void updateStatus条件更新_from匹配返回1并刷新updatedAt() {
        long id = insertDraftTicket(100L, LocalDateTime.now());
        LocalDateTime changedAt = LocalDateTime.now().plusMinutes(5);

        assertEquals(1, dao.updateStatus(id, TicketStatus.DRAFT, TicketStatus.SUBMITTED, changedAt));
        Ticket found = dao.findById(id);
        assertEquals(TicketStatus.SUBMITTED, found.getStatus());
        assertEquals(changedAt, found.getUpdatedAt());
    }

    @Test
    void updateStatus条件更新_from不匹配返回0且状态不变() {
        long id = insertDraftTicket(100L, LocalDateTime.now());

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
        long id = insertDraftTicket(100L, LocalDateTime.now());
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
        insertDraftTicket(100L, base.minusHours(3));
        insertDraftTicket(200L, base.minusHours(2));
        insertDraftTicket(100L, base.minusHours(1));

        List<Ticket> mine = dao.listByCreator(100L);
        assertEquals(2, mine.size());
        assertEquals(1, dao.listByCreator(200L).size());
        // 最新在前
        assertEquals(base.minusHours(1), mine.get(0).getCreatedAt());
        assertEquals(base.minusHours(3), mine.get(1).getCreatedAt());
    }

    @Test
    void listByStatus_仅返回匹配状态() {
        long id = insertDraftTicket(100L, LocalDateTime.now());
        dao.updateStatus(id, TicketStatus.DRAFT, TicketStatus.SUBMITTED, LocalDateTime.now());
        insertDraftTicket(200L, LocalDateTime.now());

        assertEquals(1, dao.listByStatus(TicketStatus.SUBMITTED).size());
        assertEquals(1, dao.listByStatus(TicketStatus.DRAFT).size());
        assertEquals(0, dao.listByStatus(TicketStatus.CLOSED).size());
    }

    @Test
    void findById返回快照_修改返回值不影响内部状态() {
        long id = insertDraftTicket(100L, LocalDateTime.now());
        List<Long> instances = new ArrayList<>(List.of(1L));

        Ticket snapshot = dao.findById(id);
        snapshot.setStatus(TicketStatus.CLOSED);
        snapshot.getInstanceIds().add(999L);

        Ticket fresh = dao.findById(id);
        assertEquals(TicketStatus.DRAFT, fresh.getStatus());
        assertEquals(List.of(1L), fresh.getInstanceIds());
    }
}
