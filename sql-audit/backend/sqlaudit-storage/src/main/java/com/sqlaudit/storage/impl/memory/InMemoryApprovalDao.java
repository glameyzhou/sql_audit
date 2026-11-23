package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.common.enums.ApproverType;
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
 * {@link ApprovalDao} 的内存 fake 实现（单测替身）。
 *
 * <p>语义逐条对齐接口 javadoc（README §4.6 会签/或签与防自审由引擎层守卫）：</p>
 * <ul>
 *   <li>{@code insert} 撞「同一工单同一组同样的指派对象」返回 0（模拟 JDBC 版唯一键冲突
 *       返回 0 的契约），调用方据此判「已被并发生成」；</li>
 *   <li>{@code updateResult} 的「检查 fromResult + 写入」在 synchronized 块内原子完成
 *       （模拟 {@code UPDATE approval SET result=? WHERE id=? AND result=?}），
 *       已被决策的记录二次更新返回 0，不会被覆盖；{@code decidedBy} 写「实际决策人」，
 *       指派对象（approverType / approverRef）不被覆盖；</li>
 *   <li>{@code listByTicket} 按 (nodeOrder, id) 稳定排序，同 nodeOrder 多行即会签/或签组；</li>
 *   <li>{@code transfer} 在同一 synchronized 块内两步完成（原记录置 TRANSFERRED 留痕 +
 *       插入指向代理人的 USER 记录），任一步失败整笔不做——与 JDBC 版的事务原子性等价；
 *       代理人已在本组（撞唯一键）返回 0 且原记录保持 PENDING；</li>
 *   <li>查询方法返回快照拷贝，调用方修改返回值不影响内部状态。</li>
 * </ul>
 */
public class InMemoryApprovalDao implements ApprovalDao {

    private final Map<Long, Approval> store = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    /** 唯一键：ticketId + nodeOrder + approverType + approverRef。 */
    private static String assigneeKey(Approval a) {
        return a.getTicketId() + "|" + a.getNodeOrder() + "|" + a.getApproverType() + "|" + a.getApproverRef();
    }

    @Override
    public synchronized long insert(Approval approval) {
        // 模拟 uk_approval_assignee 唯一约束：同组同指派对象已存在 → 返回 0 不插入
        String key = assigneeKey(approval);
        for (Approval existing : store.values()) {
            if (assigneeKey(existing).equals(key)) {
                return 0L;
            }
        }
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
    public synchronized int updateResult(long id, long decidedBy, ApprovalResult fromResult, ApprovalResult toResult,
                                         String comment, LocalDateTime decidedAt) {
        Approval approval = store.get(id);
        if (approval == null) {
            return 0;
        }
        // 条件更新：result 不匹配（已被决策）→ 影响行数 0，不覆盖既有决策
        if (fromResult != null && fromResult == approval.getResult()) {
            approval.setDecidedBy(decidedBy);
            approval.setResult(toResult);
            approval.setComment(comment);
            approval.setDecidedAt(decidedAt);
            return 1;
        }
        return 0;
    }

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
    public synchronized long transfer(long id, long fromUserId, long toUserId, LocalDateTime transferredAt) {
        Approval approval = store.get(id);
        // 条件：记录存在且仍为 PENDING（已被决策 / 不存在 → 0，不做任何写入）
        if (approval == null || ApprovalResult.PENDING != approval.getResult()) {
            return 0L;
        }
        // 第一步：原记录置 TRANSFERRED 留痕（decided_by = 转交人）
        approval.setResult(ApprovalResult.TRANSFERRED);
        approval.setDecidedBy(fromUserId);
        approval.setDecidedAt(transferredAt);
        // 第二步：插入指向代理人的 USER 记录；代理人已在本组（撞唯一键）→ 整笔撤销
        Approval delegate = new Approval();
        delegate.setTicketId(approval.getTicketId());
        delegate.setFlowNodeId(approval.getFlowNodeId());
        delegate.setNodeOrder(approval.getNodeOrder());
        delegate.setMode(approval.getMode());
        delegate.setApproverType(ApproverType.USER);
        delegate.setApproverRef(String.valueOf(toUserId));
        delegate.setResult(ApprovalResult.PENDING);
        delegate.setCreatedAt(transferredAt);
        long inserted = insert(delegate);
        if (inserted == 0L) {
            approval.setResult(ApprovalResult.PENDING);
            approval.setDecidedBy(null);
            approval.setDecidedAt(null);
            return 0L;
        }
        return inserted;
    }

    /** 深拷贝：返回给调用方的是快照，防止绕过条件更新直接改内存状态。 */
    private static Approval copy(Approval src) {
        Approval dest = new Approval();
        dest.setId(src.getId());
        dest.setTicketId(src.getTicketId());
        dest.setFlowNodeId(src.getFlowNodeId());
        dest.setNodeOrder(src.getNodeOrder());
        dest.setMode(src.getMode());
        dest.setApproverType(src.getApproverType());
        dest.setApproverRef(src.getApproverRef());
        dest.setResult(src.getResult());
        dest.setDecidedBy(src.getDecidedBy());
        dest.setComment(src.getComment());
        dest.setDecidedAt(src.getDecidedAt());
        dest.setCreatedAt(src.getCreatedAt());
        return dest;
    }
}
