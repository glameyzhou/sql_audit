package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.model.Approval;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ApprovalDao} 的内存 fake 实现（B-3，供 workflow 单测解耦真实数据库）。
 *
 * <p>语义逐条对齐接口 javadoc（设计文档 §8.2 会签/或签、§3.4 防自审由引擎层守卫）：</p>
 * <ul>
 *   <li>{@code updateResult} 的「检查 fromResult + 写入」在 synchronized 块内原子完成
 *       （模拟 {@code UPDATE approval SET result=? WHERE id=? AND result=?}），
 *       已被决策的记录二次更新返回 0，不会被覆盖；</li>
 *   <li>{@code listByTicket} 按 (nodeOrder, id) 稳定排序，同 nodeOrder 多行即会签/或签组；</li>
 *   <li>查询方法返回快照拷贝，调用方修改返回值不影响内部状态。</li>
 * </ul>
 */
public class InMemoryApprovalDao implements ApprovalDao {

    private final Map<Long, Approval> store = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    @Override
    public synchronized long insert(Approval approval) {
        long id = idGenerator.incrementAndGet();
        approval.setId(id);
        store.put(id, copy(approval));
        return id;
    }

    @Override
    public synchronized List<Approval> listByTicket(long ticketId) {
        List<Approval> result = new ArrayList<>();
        for (Approval approval : store.values()) {
            if (approval.getTicketId() != null && approval.getTicketId() == ticketId) {
                result.add(copy(approval));
            }
        }
        // (nodeOrder, id) 稳定排序：节点串行推进，同 nodeOrder 多行 = 会签/或签组
        result.sort(Comparator.comparing(Approval::getNodeOrder)
                .thenComparing(Approval::getId));
        return result;
    }

    @Override
    public synchronized int updateResult(long id, long approverId, ApprovalResult fromResult, ApprovalResult toResult,
                                         String comment, LocalDateTime decidedAt) {
        Approval approval = store.get(id);
        if (approval == null) {
            return 0;
        }
        // 条件更新：result 不匹配（已被决策）→ 影响行数 0，不覆盖既有决策
        if (fromResult != null && fromResult == approval.getResult()) {
            approval.setApproverId(approverId);
            approval.setResult(toResult);
            approval.setComment(comment);
            approval.setDecidedAt(decidedAt);
            return 1;
        }
        return 0;
    }

    @Override
    public synchronized List<Approval> listPendingCreatedBefore(LocalDateTime threshold) {
        List<Approval> result = new ArrayList<>();
        for (Approval approval : store.values()) {
            boolean pending = ApprovalResult.PENDING == approval.getResult();
            boolean overdue = approval.getCreatedAt() != null && approval.getCreatedAt().isBefore(threshold);
            if (pending && overdue) {
                result.add(copy(approval));
            }
        }
        result.sort(Comparator.comparing(Approval::getCreatedAt)
                .thenComparing(Approval::getId));
        return result;
    }

    /** 全部 PENDING 审批（server「待我审批」列表用；非接口方法）。 */
    @Override
    public synchronized List<Approval> listPending() {
        List<Approval> result = new ArrayList<>();
        for (Approval approval : store.values()) {
            if (ApprovalResult.PENDING == approval.getResult()) {
                result.add(copy(approval));
            }
        }
        result.sort(Comparator.comparing(Approval::getCreatedAt)
                .thenComparing(Approval::getId));
        return result;
    }

    @Override
    public synchronized int transfer(long id, long fromUserId, long toUserId) {
        Approval approval = store.get(id);
        if (approval == null) {
            return 0;
        }
        // 条件更新：仍为 PENDING 且归属 fromUserId 才转交
        if (ApprovalResult.PENDING == approval.getResult()
                && approval.getApproverId() != null && approval.getApproverId() == fromUserId) {
            approval.setApproverId(toUserId);
            return 1;
        }
        return 0;
    }

    /** 深拷贝：返回给调用方的是快照，防止绕过条件更新直接改内存状态。 */
    private static Approval copy(Approval src) {
        Approval dest = new Approval();
        dest.setId(src.getId());
        dest.setTicketId(src.getTicketId());
        dest.setNodeId(src.getNodeId());
        dest.setNodeOrder(src.getNodeOrder());
        dest.setApproverId(src.getApproverId());
        dest.setApproverType(src.getApproverType());
        dest.setApproverRef(src.getApproverRef());
        dest.setMode(src.getMode());
        dest.setResult(src.getResult());
        dest.setComment(src.getComment());
        dest.setDecidedAt(src.getDecidedAt());
        dest.setCreatedAt(src.getCreatedAt());
        return dest;
    }
}
