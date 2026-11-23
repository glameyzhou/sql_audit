package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.common.enums.ApproverType;
import com.sqlaudit.storage.model.Approval;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryApprovalDao} 单测：条件更新（PENDING → 终态只允许一次）、
 * 指派唯一（并发重复生成返回 0）、查询语义与转交原子性。
 */
class InMemoryApprovalDaoTest {

    private final InMemoryApprovalDao dao = new InMemoryApprovalDao();

    private long insertApproval(long ticketId, int nodeOrder, ApproverType type, String ref,
                                ApprovalMode mode, ApprovalResult result, LocalDateTime createdAt) {
        Approval approval = new Approval();
        approval.setTicketId(ticketId);
        approval.setFlowNodeId(501L);
        approval.setNodeOrder(nodeOrder);
        approval.setApproverType(type);
        approval.setApproverRef(ref);
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
        insertApproval(t1, 2, ApproverType.ROLE, "DBA", ApprovalMode.OR, ApprovalResult.PENDING, now);
        long firstOfNode1 = insertApproval(t1, 1, ApproverType.USER, "10", ApprovalMode.AND, ApprovalResult.PENDING, now);
        long secondOfNode1 = insertApproval(t1, 1, ApproverType.ROLE, "LEAD", ApprovalMode.AND, ApprovalResult.PENDING, now);
        insertApproval(2L, 1, ApproverType.INSTANCE_OWNER, "30", ApprovalMode.OR, ApprovalResult.PENDING, now);

        List<Approval> rows = dao.listByTicket(t1);
        assertEquals(3, rows.size());
        assertEquals(firstOfNode1, rows.get(0).getId());
        assertEquals(secondOfNode1, rows.get(1).getId());
        assertEquals(2, rows.get(2).getNodeOrder());
        assertTrue(rows.stream().allMatch(r -> r.getTicketId() == t1));
        assertEquals(501L, rows.get(0).getFlowNodeId(), "flow_node_id 记录生成它的流程节点");
        assertNull(rows.get(0).getDecidedBy(), "未决策时 decided_by 为空（与指派对象分列）");
    }

    @Test
    void insert_同组同指派对象已存在返回0() {
        LocalDateTime now = LocalDateTime.now();
        assertTrue(insertApproval(1L, 1, ApproverType.ROLE, "LEAD", ApprovalMode.OR,
                ApprovalResult.PENDING, now) > 0);
        // 同 (ticketId, nodeOrder, type, ref) 重复 → 0（模拟唯一约束，并发重复生成的判据）
        assertEquals(0L, insertApproval(1L, 1, ApproverType.ROLE, "LEAD", ApprovalMode.OR,
                ApprovalResult.PENDING, now));
        // 同组不同 ref（代理人转交的新记录）→ 正常插入
        assertTrue(insertApproval(1L, 1, ApproverType.USER, "20", ApprovalMode.OR,
                ApprovalResult.PENDING, now) > 0);
        // 不同工单同 ref → 正常插入
        assertTrue(insertApproval(2L, 1, ApproverType.ROLE, "LEAD", ApprovalMode.OR,
                ApprovalResult.PENDING, now) > 0);
    }

    @Test
    void updateResult条件更新_PENDING到APPROVED成功并记录决策人() {
        long id = insertApproval(1L, 1, ApproverType.ROLE, "LEAD", ApprovalMode.AND, ApprovalResult.PENDING, LocalDateTime.now());
        LocalDateTime decidedAt = LocalDateTime.now().plusMinutes(10);

        assertEquals(1, dao.updateResult(id, 30L, ApprovalResult.PENDING, ApprovalResult.APPROVED, "同意", decidedAt));
        Approval found = dao.listByTicket(1L).get(0);
        assertEquals(ApprovalResult.APPROVED, found.getResult());
        assertEquals(30L, found.getDecidedBy(), "实际决策人写入 decided_by（认领 ROLE 记录）");
        assertEquals("LEAD", found.getApproverRef(), "指派对象不被覆盖");
        assertEquals(ApproverType.ROLE, found.getApproverType());
        assertEquals("同意", found.getComment());
        assertEquals(decidedAt, found.getDecidedAt());
    }

    @Test
    void updateResult条件更新_已决策记录返回0且不被覆盖() {
        long id = insertApproval(1L, 1, ApproverType.USER, "10", ApprovalMode.AND, ApprovalResult.PENDING, LocalDateTime.now());
        assertEquals(1, dao.updateResult(id, 10L, ApprovalResult.PENDING, ApprovalResult.APPROVED, "ok", LocalDateTime.now()));

        // 已是 APPROVED：以 PENDING 为条件的更新（无论目标是什么）都失败
        assertEquals(0, dao.updateResult(id, 10L, ApprovalResult.PENDING, ApprovalResult.REJECTED, "改主意", LocalDateTime.now()));
        Approval found = dao.listByTicket(1L).get(0);
        assertEquals(ApprovalResult.APPROVED, found.getResult());
        assertEquals("ok", found.getComment(), "comment 不被覆盖");
    }

    @Test
    void updateResult_支持SKIPPED与TRANSFERRED() {
        long skipped = insertApproval(1L, 1, ApproverType.USER, "10", ApprovalMode.OR, ApprovalResult.PENDING, LocalDateTime.now());
        assertEquals(1, dao.updateResult(skipped, 10L, ApprovalResult.PENDING, ApprovalResult.SKIPPED, "解析到提交人本人", LocalDateTime.now()));
        assertEquals(ApprovalResult.SKIPPED, dao.listByTicket(1L).get(0).getResult());

        long transferred = insertApproval(2L, 1, ApproverType.ROLE, "LEAD", ApprovalMode.OR, ApprovalResult.PENDING, LocalDateTime.now());
        assertEquals(1, dao.updateResult(transferred, 10L, ApprovalResult.PENDING, ApprovalResult.TRANSFERRED, "转交 u20", LocalDateTime.now()));
        assertEquals(ApprovalResult.TRANSFERRED, dao.listByTicket(2L).get(0).getResult());
    }

    @Test
    void updateResult_记录不存在返回0() {
        assertEquals(0, dao.updateResult(999L, 10L, ApprovalResult.PENDING, ApprovalResult.APPROVED, null, LocalDateTime.now()));
    }

    @Test
    void listPending_全部PENDING按创建时间排序() {
        LocalDateTime now = LocalDateTime.now();
        long older = insertApproval(1L, 1, ApproverType.ROLE, "LEAD", ApprovalMode.AND, ApprovalResult.PENDING, now.minusHours(2));
        long newer = insertApproval(2L, 1, ApproverType.ROLE, "DBA", ApprovalMode.OR, ApprovalResult.PENDING, now.minusMinutes(5));
        insertApproval(3L, 1, ApproverType.ROLE, "LEAD", ApprovalMode.AND, ApprovalResult.APPROVED, now.minusHours(3)); // 非 PENDING 不含

        List<Approval> pending = dao.listPending();
        assertEquals(2, pending.size());
        assertEquals(older, pending.get(0).getId());
        assertEquals(newer, pending.get(1).getId());
    }

    @Test
    void transfer_原子两步_原记录TRANSFERRED留痕_新记录指向代理人() {
        LocalDateTime now = LocalDateTime.now();
        long id = insertApproval(1L, 1, ApproverType.ROLE, "LEAD", ApprovalMode.OR, ApprovalResult.PENDING, now);

        long newId = dao.transfer(id, 10L, 20L, now.plusMinutes(1));
        assertTrue(newId > 0, "转交成功返回新记录 id");

        List<Approval> rows = dao.listByTicket(1L);
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
    void transfer_代理人已在本组_整笔撤销原记录保持PENDING() {
        LocalDateTime now = LocalDateTime.now();
        long id = insertApproval(1L, 1, ApproverType.ROLE, "LEAD", ApprovalMode.OR, ApprovalResult.PENDING, now);
        insertApproval(1L, 1, ApproverType.USER, "20", ApprovalMode.OR, ApprovalResult.PENDING, now);

        assertEquals(0L, dao.transfer(id, 10L, 20L, now.plusMinutes(1)), "代理人已在本组（撞唯一键）返回 0");
        // 原记录保持 PENDING（内存版与 JDBC 版一致：两步同事务，任一步失败整笔撤销）
        List<Approval> rows = dao.listByTicket(1L);
        assertEquals(ApprovalResult.PENDING, rows.get(0).getResult());
        assertNull(rows.get(0).getDecidedBy());
    }

    @Test
    void transfer_记录不存在或已决策_返回0() {
        LocalDateTime now = LocalDateTime.now();
        assertEquals(0L, dao.transfer(999L, 10L, 20L, now), "记录不存在");

        long decided = insertApproval(1L, 1, ApproverType.ROLE, "LEAD", ApprovalMode.OR, ApprovalResult.PENDING, now);
        assertEquals(1, dao.updateResult(decided, 30L, ApprovalResult.PENDING, ApprovalResult.APPROVED, "ok", now));
        assertEquals(0L, dao.transfer(decided, 10L, 20L, now), "已决策的记录不可转交");
        assertEquals(1, dao.listByTicket(1L).size(), "不做任何写入");
    }

    @Test
    void listByTicket返回快照_修改返回值不影响内部状态() {
        insertApproval(1L, 1, ApproverType.USER, "10", ApprovalMode.AND, ApprovalResult.PENDING, LocalDateTime.now());
        Approval snapshot = dao.listByTicket(1L).get(0);
        snapshot.setResult(ApprovalResult.APPROVED);

        assertEquals(ApprovalResult.PENDING, dao.listByTicket(1L).get(0).getResult());
    }
}
