package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.common.enums.TicketStatus;
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
 * {@link TicketDao} 的内存 fake 实现（单测替身，不加密）。
 *
 * <p>语义逐条对齐接口 javadoc（README §4.2 工单状态机）：</p>
 * <ul>
 *   <li>{@code updateStatus} 的「检查 fromStatus + 写入」在 synchronized 块内原子完成
 *       （条件更新语义，模拟 {@code UPDATE ticket SET status=? WHERE id=? AND status=?}），
 *       并发竞争下同一 fromStatus 只有一个调用方拿到影响行数 1——乐观并发；</li>
 *   <li>{@code listByInstanceIds} 空集合返回空列表（不等于「不过滤」）；</li>
 *   <li>查询方法返回快照拷贝，调用方修改返回值不影响内部状态。</li>
 * </ul>
 *
 * <p>与 JDBC 版的差异：{@code sqlText} 明文存取（JDBC 版经 {@code SqlCipher} 加密落库）。</p>
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
    public synchronized int updateStatus(long id, TicketStatus fromStatus, TicketStatus toStatus, LocalDateTime changedAt) {
        Ticket ticket = store.get(id);
        if (ticket == null) {
            return 0;
        }
        // 条件更新：fromStatus 不匹配（已被并发修改）→ 影响行数 0
        if (fromStatus != null && fromStatus == ticket.getStatus()) {
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
    public synchronized List<Ticket> listByCreator(long creatorId, int offset, int limit) {
        List<Ticket> sorted = listByCreator(creatorId);
        return page(sorted, offset, limit);
    }

    @Override
    public synchronized long countByCreator(long creatorId) {
        return store.values().stream()
                .filter(t -> t.getCreatorId() != null && t.getCreatorId() == creatorId)
                .count();
    }

    @Override
    public synchronized List<Ticket> listAll() {
        List<Ticket> result = new ArrayList<>();
        for (Ticket ticket : store.values()) {
            result.add(copy(ticket));
        }
        result.sort(Comparator.comparing(Ticket::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Ticket::getId, Comparator.reverseOrder()));
        return result;
    }

    @Override
    public synchronized List<Ticket> listAll(int offset, int limit) {
        return page(listAll(), offset, limit);
    }

    @Override
    public synchronized long countAll() {
        return store.size();
    }

    @Override
    public synchronized List<Ticket> listByInstanceIds(List<Long> instanceIds) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            // 空集合 = 数据范围为空，返回空列表（不等于「不过滤」）
            return List.of();
        }
        List<Ticket> result = new ArrayList<>();
        for (Ticket ticket : store.values()) {
            if (ticket.getInstanceId() != null && instanceIds.contains(ticket.getInstanceId())) {
                result.add(copy(ticket));
            }
        }
        result.sort(Comparator.comparing(Ticket::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Ticket::getId, Comparator.reverseOrder()));
        return result;
    }

    @Override
    public synchronized List<Ticket> listByInstanceIds(List<Long> instanceIds, int offset, int limit) {
        return page(listByInstanceIds(instanceIds), offset, limit);
    }

    @Override
    public synchronized long countByInstanceIds(List<Long> instanceIds) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            // 空集合 = 数据范围为空，计数 0（不等于「不过滤」）
            return 0L;
        }
        return store.values().stream()
                .filter(t -> t.getInstanceId() != null && instanceIds.contains(t.getInstanceId()))
                .count();
    }

    @Override
    public synchronized List<Ticket> listByStatus(TicketStatus status) {
        List<Ticket> result = new ArrayList<>();
        for (Ticket ticket : store.values()) {
            if (status == ticket.getStatus()) {
                result.add(copy(ticket));
            }
        }
        result.sort(Comparator.comparing(Ticket::getId));
        return result;
    }

    @Override
    public synchronized int markNeedsSecondConfirm(long id, boolean required) {
        Ticket ticket = store.get(id);
        if (ticket == null) {
            return 0;
        }
        ticket.setNeedsSecondConfirm(required);
        return 1;
    }

    @Override
    public synchronized int updateSecondConfirm(long id, Long confirmedBy, LocalDateTime confirmedAt) {
        Ticket ticket = store.get(id);
        if (ticket == null) {
            return 0;
        }
        ticket.setConfirmedBy(confirmedBy);
        ticket.setConfirmedAt(confirmedAt);
        return 1;
    }

    /** 分页工具：offset 从 0 起（越界截断到空），limit<0 视为取全部——与 JDBC 版 LIMIT/OFFSET 同款语义。 */
    private static <T> List<T> page(List<T> sorted, int offset, int limit) {
        int from = Math.min(Math.max(offset, 0), sorted.size());
        int to = limit < 0 ? sorted.size() : Math.min(from + limit, sorted.size());
        return new ArrayList<>(sorted.subList(from, to));
    }

    /** 深拷贝：返回给调用方的是快照，防止绕过条件更新直接改内存状态。 */
    private static Ticket copy(Ticket src) {
        Ticket dest = new Ticket();
        dest.setId(src.getId());
        dest.setTitle(src.getTitle());
        dest.setCreatorId(src.getCreatorId());
        dest.setInstanceId(src.getInstanceId());
        dest.setRiskLevel(src.getRiskLevel());
        dest.setStatus(src.getStatus());
        dest.setRollbackSummary(src.getRollbackSummary());
        dest.setSqlText(src.getSqlText());
        dest.setShardConfig(src.getShardConfig());
        dest.setTraceId(src.getTraceId());
        dest.setCreatedAt(src.getCreatedAt());
        dest.setUpdatedAt(src.getUpdatedAt());
        dest.setNeedsSecondConfirm(src.isNeedsSecondConfirm());
        dest.setConfirmedBy(src.getConfirmedBy());
        dest.setConfirmedAt(src.getConfirmedAt());
        return dest;
    }
}
