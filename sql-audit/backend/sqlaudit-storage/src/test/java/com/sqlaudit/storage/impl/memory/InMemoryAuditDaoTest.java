package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.storage.model.AuditLog;
import com.sqlaudit.storage.model.ChainHead;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryAuditDao} 单测：创世链头、append 流程、seq 区间读取。
 */
class InMemoryAuditDaoTest {

    private final InMemoryAuditDao dao = new InMemoryAuditDao();

    @Test
    void 首条链头_lastSeq为0_lastHash是64个0() {
        ChainHead head = dao.lockChainHead();
        dao.forceUnlockChain(); // 只读校验，走测试兜底释放

        assertEquals(0L, head.getLastSeq());
        assertEquals("0".repeat(64), head.getLastHash());
    }

    @Test
    void append流程_lock读链头_insert_updateChainHead() {
        // 第一条：prev_hash = 创世 hash
        ChainHead head1 = dao.lockChainHead();
        assertEquals(0L, head1.getLastSeq());
        assertEquals("0".repeat(64), head1.getLastHash());

        AuditLog first = makeLog(1L, head1.getLastHash(), "hash-1");
        assertEquals(1, dao.insert(first));
        assertEquals(1, dao.updateChainHead(1L, "hash-1"));

        // 第二条：链头已推进
        ChainHead head2 = dao.lockChainHead();
        assertEquals(1L, head2.getLastSeq());
        assertEquals("hash-1", head2.getLastHash());

        AuditLog second = makeLog(2L, head2.getLastHash(), "hash-2");
        assertEquals(1, dao.insert(second));
        assertEquals(1, dao.updateChainHead(2L, "hash-2"));

        ChainHead current = dao.peekChainHead();
        assertEquals(2L, current.getLastSeq());
        assertEquals("hash-2", current.getLastHash());
    }

    @Test
    void insert_自增id且不覆盖() {
        dao.insert(makeLog(1L, "0".repeat(64), "hash-1"));
        dao.insert(makeLog(2L, "hash-1", "hash-2"));

        List<AuditLog> logs = dao.listBySeqRange(1L, 2L);
        assertEquals(2, logs.size());
        assertEquals(1L, logs.get(0).getSeq());
        assertEquals(2L, logs.get(1).getSeq());
        assertTrue(logs.get(0).getId() != logs.get(1).getId());
    }

    @Test
    void listBySeqRange_区间边界与排序() {
        for (long seq = 1; seq <= 5; seq++) {
            dao.insert(makeLog(seq, "prev-" + seq, "hash-" + seq));
        }
        // 乱序插入后再查：按 seq 升序返回
        dao.insert(makeLog(7L, "prev-7", "hash-7"));
        dao.insert(makeLog(6L, "prev-6", "hash-6"));

        List<AuditLog> range = dao.listBySeqRange(2L, 6L);
        assertEquals(5, range.size());
        for (int i = 0; i < range.size(); i++) {
            assertEquals(2L + i, range.get(i).getSeq());
        }

        assertEquals(0, dao.listBySeqRange(100L, 200L).size());
    }

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
        log.setResult("success");
        log.setPrevHash(prevHash);
        log.setHash(hash);
        return log;
    }
}
