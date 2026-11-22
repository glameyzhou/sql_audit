package com.sqlaudit.audit;

import com.sqlaudit.common.model.AuditEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChainVerifier 测试：完整链通过 / 篡改 hash / 篡改内容 / 篡改 detail 密文 /
 * 删除中间记录 / 子区间校验（B-2 验收：篡改任一行后校验必须报错）。
 */
class ChainVerifierTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private InMemoryAuditDao dao;
    private AuditWriter writer;
    private ChainVerifier verifier;

    @BeforeEach
    void setUp() {
        dao = new InMemoryAuditDao();
        writer = new AuditWriter(dao, new NoopDetailCipher(), ZONE);
        verifier = new ChainVerifier(dao, new NoopDetailCipher(), ZONE);
    }

    private void appendChain(int count) {
        for (int i = 1; i <= count; i++) {
            writer.append(AuditEvent.builder()
                    .actorId(1000 + i)
                    .actorName("user-" + i)
                    .actorRole("DBA")
                    .action("UNIT_EXEC")
                    .targetType("TICKET_UNIT")
                    .targetId(String.valueOf(i))
                    .instanceId(7L)
                    .detailPlaintext("UPDATE t SET a=" + i + " WHERE id=" + i)
                    .result("SUCCESS")
                    .clientIp("10.0.0.8")
                    .userAgent("Mozilla/5.0")
                    .traceId("trc-" + i)
                    .createdAt(OffsetDateTime.of(2026, 11, 22, 16, 30, 0, 123_000_000, ZoneOffset.ofHours(8)))
                    .build());
        }
    }

    @Test
    void intactChain_verifiesOk() {
        appendChain(20);

        ChainVerifyResult result = verifier.verify(1, 20);

        assertTrue(result.isOk(), "未篡改的链必须通过: " + result);
        assertEquals(20, result.getCheckedCount());
        assertNull(result.getBreakSeq());
    }

    @Test
    void tamperedHash_detected() {
        appendChain(10);

        // 直接改掉 seq=5 的存储 hash
        dao.get(5L).setHash("f".repeat(64));

        ChainVerifyResult result = verifier.verify(1, 10);

        assertFalse(result.isOk(), "篡改 hash 后校验必须报错");
        assertEquals(5L, result.getBreakSeq(), "断裂点必须是 seq=5");
        assertTrue(result.getReason().contains("hash 重算不一致"), "原因应为重算不一致: " + result.getReason());
    }

    @Test
    void tamperedContent_detected() {
        appendChain(10);

        // 改掉 seq=3 的业务内容（不动 hash 字段）——重算必然对不上
        dao.get(3L).setActorName("mallory");

        ChainVerifyResult result = verifier.verify(1, 10);

        assertFalse(result.isOk(), "篡改内容后校验必须报错");
        assertEquals(3L, result.getBreakSeq());
        assertTrue(result.getReason().contains("hash 重算不一致"), "原因应为重算不一致: " + result.getReason());
    }

    @Test
    void tamperedDetailCiphertext_detected() {
        appendChain(10);

        // 改掉 seq=7 的 detail 密文（Noop 下即明文被换）
        dao.get(7L).setDetail("DROP TABLE audit_log".getBytes());

        ChainVerifyResult result = verifier.verify(1, 10);

        assertFalse(result.isOk(), "篡改 detail 后校验必须报错");
        assertEquals(7L, result.getBreakSeq());
    }

    @Test
    void tamperedPrevHash_detectedAsLinkBreak() {
        appendChain(10);

        // 改掉 seq=6 的 prev_hash：与上一条（seq=5）的 hash 不衔接
        dao.get(6L).setPrevHash("ab".repeat(32));

        ChainVerifyResult result = verifier.verify(1, 10);

        assertFalse(result.isOk());
        assertEquals(6L, result.getBreakSeq());
        assertTrue(result.getReason().contains("不衔接"), "原因应为 prev_hash 不衔接: " + result.getReason());
    }

    @Test
    void deletedMiddleRecord_detected() {
        appendChain(10);

        // 删除 seq=4：seq=5 的 prev_hash 指向已消失的 hash(4)，与 hash(3) 不衔接
        dao.delete(4L);

        ChainVerifyResult result = verifier.verify(1, 10);

        assertFalse(result.isOk(), "删除中间记录后校验必须报错（prev_hash 不衔接）");
        assertEquals(5L, result.getBreakSeq(), "断裂点应是缺失记录的下一条");
        assertTrue(result.getReason().contains("不衔接"), "原因应为 prev_hash 不衔接: " + result.getReason());
    }

    @Test
    void subRangeVerify_usesPreviousRecordAsAnchor() {
        appendChain(10);

        // 只校验 [4, 8]：锚点取 seq=3 的 hash
        ChainVerifyResult result = verifier.verify(4, 8);

        assertTrue(result.isOk(), "子区间（锚点存在）必须通过: " + result);
        assertEquals(5, result.getCheckedCount());

        // 篡改锚点前一条（seq=3）不影响 [4,8] 的衔接校验，但篡改 seq=4 的内容必须被抓到
        dao.get(4L).setResult("DENIED");
        ChainVerifyResult tampered = verifier.verify(4, 8);
        assertFalse(tampered.isOk());
        assertEquals(4L, tampered.getBreakSeq());
    }

    @Test
    void emptyRange_returnsOkWithZeroChecked() {
        appendChain(5);

        ChainVerifyResult result = verifier.verify(6, 10); // 区间内无记录

        assertTrue(result.isOk());
        assertEquals(0, result.getCheckedCount());
    }

    @Test
    void invalidRange_reportedAsBreak() {
        ChainVerifyResult result = verifier.verify(0, 10);
        assertFalse(result.isOk());
        assertTrue(result.getReason().contains("非法区间"));
    }
}
