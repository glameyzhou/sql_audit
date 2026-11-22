package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.storage.dao.TicketDao;
import com.sqlaudit.storage.model.Ticket;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link TicketDao} 的内存 fake 实现（B-3，供 workflow 单测解耦真实数据库）。
 *
 * <p>语义逐条对齐接口 javadoc（设计文档 §3.2 工单状态机）：</p>
 * <ul>
 *   <li>{@code updateStatus} 的「检查 fromStatus + 写入」在 synchronized 块内原子完成
 *       （条件更新语义，模拟 {@code UPDATE ticket SET status=? WHERE id=? AND status=?}），
 *       并发竞争下同一 fromStatus 只有一个调用方拿到影响行数 1——乐观并发；</li>
 *   <li>查询方法返回快照拷贝（instanceIds 深拷贝），调用方修改返回值不影响内部状态。</li>
 * </ul>
 */
public class InMemoryTicketDao implements TicketDao {

    private final Map<Long, Ticket> store = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    @Override
    public synchronized long insertTicket(Ticket ticket) {
        long id = idGenerator.incrementAndGet();
        ticket.setId(id);
        store.put(id, copy(ticket));
        return id;
    }

    @Override
    public synchronized Ticket findById(long id) {
        Ticket ticket = store.get(id);
        return ticket == null ? null : copy(ticket);
    }

    @Override
    public synchronized int updateStatus(long id, String fromStatus, String toStatus, LocalDateTime changedAt) {
        Ticket ticket = store.get(id);
        if (ticket == null) {
            return 0;
        }
        // 条件更新：fromStatus 不匹配（已被并发修改）→ 影响行数 0
        if (fromStatus != null && fromStatus.equals(ticket.getStatus())) {
            ticket.setStatus(toStatus);
            ticket.setUpdatedAt(changedAt);
            return 1;
        }
        return 0;
    }

    @Override
    public synchronized List<Ticket> listByCreator(long creatorId) {
        List<Ticket> result = new ArrayList<>();
        for (Ticket ticket : store.values()) {
            if (ticket.getCreatorId() != null && ticket.getCreatorId() == creatorId) {
                result.add(copy(ticket));
            }
        }
        // 「我的工单」：最新在前；createdAt 相同按 id 倒序保证稳定
        result.sort(Comparator.comparing(Ticket::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Ticket::getId, Comparator.reverseOrder()));
        return result;
    }

    @Override
    public synchronized List<Ticket> listByStatus(String status) {
        List<Ticket> result = new ArrayList<>();
        for (Ticket ticket : store.values()) {
            if (status == null ? ticket.getStatus() == null : status.equals(ticket.getStatus())) {
                result.add(copy(ticket));
            }
        }
        result.sort(Comparator.comparing(Ticket::getId));
        return result;
    }

    /** 深拷贝：返回给调用方的是快照，防止绕过条件更新直接改内存状态。 */
    private static Ticket copy(Ticket src) {
        Ticket dest = new Ticket();
        dest.setId(src.getId());
        dest.setTitle(src.getTitle());
        dest.setCreatorId(src.getCreatorId());
        dest.setInstanceIds(src.getInstanceIds() == null ? null : new ArrayList<>(src.getInstanceIds()));
        dest.setEnvironment(src.getEnvironment());
        dest.setRiskLevel(src.getRiskLevel());
        dest.setStatus(src.getStatus());
        dest.setRollbackSummary(src.getRollbackSummary());
        dest.setScheduledAt(src.getScheduledAt());
        dest.setTraceId(src.getTraceId());
        dest.setCreatedAt(src.getCreatedAt());
        dest.setUpdatedAt(src.getUpdatedAt());
        return dest;
    }
}
