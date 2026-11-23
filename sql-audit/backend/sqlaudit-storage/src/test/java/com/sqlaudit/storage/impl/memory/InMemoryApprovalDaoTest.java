package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.storage.model.Approval;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryApprovalDao} 单测：覆盖条件更新（PENDING → 终态只允许一次）与查询语义。
 */
class InMemoryApprovalDaoTest {

    private final InMemoryApprovalDao dao = new InMemoryApprovalDao();

    private long insertApproval(long ticketId, int nodeOrder, long approverId, ApprovalMode mode,
                                ApprovalResult result, LocalDateTime createdAt) {
        Approval approval = new Approval();
        approval.setTicketId(ticketId);
        approval.setNodeId(0L);
        approval.setNodeOrder(nodeOrder);
        approval.setApproverId(approverId);
        approval.setMode(mode);
        approval.setResult(result);
        approval.setCreatedAt(createdAt);
        return dao.insert(approval);
    }

    @Test
    void insert与listByTicket_按nodeOrder再按id稳定排序() {
        LocalDateTime now = LocalDateTime.now();
        long t1 = 1L;
        // 先插 node2 再插 node1（含同 nodeOrder 多行 = 会签组），验证排序稳定
        insertApproval(t1, 2, 0L, ApprovalMode.AND, ApprovalResult.PENDING, now);
        long firstOfNode1 = insertApproval(t1, 1, 10L, ApprovalMode.AND, ApprovalResult.PENDING, now);
        long secondOfNode1 = insertApproval(t1, 1, 20L, ApprovalMode.AND, ApprovalResult.PENDING, now);
        insertApproval(2L, 1, 0L, ApprovalMode.OR, ApprovalResult.PENDING, now);

        List<Approval> rows = dao.listByTicket(t1);
        assertEquals(3, rows.size());
        assertEquals(firstOfNode1, rows.get(0).getId());
        assertEquals(secondOfNode1, rows.get(1).getId());
        assertEquals(2, rows.get(2).getNodeOrder());
        assertTrue(rows.stream().allMatch(r -> r.getTicketId() == t1));
    }

    @Test
    void updateResult条件更新_PENDING到APPROVED成功并记录决策人() {
        long id = insertApproval(1L, 1, 0L, ApprovalMode.AND, ApprovalResult.PENDING, LocalDateTime.now());
        LocalDateTime decidedAt = LocalDateTime.now().plusMinutes(10);

        assertEquals(1, dao.updateResult(id, 30L, ApprovalResult.PENDING, ApprovalResult.APPROVED, "同意", decidedAt));
        Approval found = dao.listByTicket(1L).get(0);
        assertEquals(ApprovalResult.APPROVED, found.getResult());
        assertEquals(30L, found.getApproverId(), "ROLE 占位记录被决策人认领");
        assertEquals("同意", found.getComment());
        assertEquals(decidedAt, found.getDecidedAt());
    }

    @Test
    void updateResult条件更新_已决策记录返回0且不被覆盖() {
        long id = insertApproval(1L, 1, 10L, ApprovalMode.AND, ApprovalResult.PENDING, LocalDateTime.now());
        assertEquals(1, dao.updateResult(id, 10L, ApprovalResult.PENDING, ApprovalResult.APPROVED, "ok", LocalDateTime.now()));

        // 已是 APPROVED：以 PENDING 为条件的更新（无论目标是什么）都失败
        assertEquals(0, dao.updateResult(id, 10L, ApprovalResult.PENDING, ApprovalResult.REJECTED, "改主意", LocalDateTime.now()));
        Approval found = dao.listByTicket(1L).get(0);
        assertEquals(ApprovalResult.APPROVED, found.getResult());
        assertEquals("ok", found.getComment(), "comment 不被覆盖");
    }

    @Test
    void updateResult_支持转交TRANSFERRED() {
        long id = insertApproval(1L, 1, 10L, ApprovalMode.OR, ApprovalResult.PENDING, LocalDateTime.now());
        assertEquals(1, dao.updateResult(id, 10L, ApprovalResult.PENDING, ApprovalResult.TRANSFERRED, "转交 u20", LocalDateTime.now()));
        assertEquals(ApprovalResult.TRANSFERRED, dao.listByTicket(1L).get(0).getResult());
    }

    @Test
    void updateResult_记录不存在返回0() {
        assertEquals(0, dao.updateResult(999L, 10L, ApprovalResult.PENDING, ApprovalResult.APPROVED, null, LocalDateTime.now()));
    }

    @Test
    void listPendingCreatedBefore_只返回超时的PENDING记录() {
        LocalDateTime now = LocalDateTime.now();
        long overduePending = insertApproval(1L, 1, 0L, ApprovalMode.AND, ApprovalResult.PENDING, now.minusHours(2));
        insertApproval(1L, 1, 20L, ApprovalMode.AND, ApprovalResult.PENDING, now.minusMinutes(5)); // 未超时
        insertApproval(1L, 1, 30L, ApprovalMode.AND, ApprovalResult.APPROVED, now.minusHours(3));  // 超时但已决策
        insertApproval(2L, 1, 0L, ApprovalMode.OR, ApprovalResult.REJECTED, now.minusHours(3));    // 超时但已决策

        List<Approval> overdue = dao.listPendingCreatedBefore(now.minusHours(1));
        assertEquals(1, overdue.size());
        assertEquals(overduePending, overdue.get(0).getId());
        assertNotNull(overdue.get(0).getCreatedAt());
    }

    @Test
    void listByTicket返回快照_修改返回值不影响内部状态() {
        insertApproval(1L, 1, 10L, ApprovalMode.AND, ApprovalResult.PENDING, LocalDateTime.now());
        Approval snapshot = dao.listByTicket(1L).get(0);
        snapshot.setResult(ApprovalResult.APPROVED);

        assertEquals(ApprovalResult.PENDING, dao.listByTicket(1L).get(0).getResult());
    }
}
