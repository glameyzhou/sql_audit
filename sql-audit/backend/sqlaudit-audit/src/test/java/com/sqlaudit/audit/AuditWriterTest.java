package com.sqlaudit.audit;

import com.sqlaudit.common.audit.CanonicalJson;
import com.sqlaudit.common.model.AuditEvent;
import com.sqlaudit.storage.model.AuditLog;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AuditWriter 测试：创世块 / 顺序写入 / 100 线程并发（B-2 验收标准）。
 *
 * <p>时区固定 Asia/Shanghai（写入与校验共用同一时区，与生产平台统一时区口径一致）。</p>
 */
class AuditWriterTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private AuditEvent sampleEvent(long n) {
        return AuditEvent.builder()
                .actorId(1000 + n)
                .actorName("user-" + n)
                .actorRole("DBA")
                .action("UNIT_EXEC")
                .targetType("TICKET_UNIT")
                .targetId(String.valueOf(n))
                .instanceId(7L)
                .detailPlaintext("UPDATE t SET a=" + n + " WHERE id=" + n)
                .result("SUCCESS")
                .clientIp("10.0.0.8")
                .userAgent("Mozilla/5.0")
                .traceId("trc-" + n)
                .createdAt(OffsetDateTime.of(2026, 11, 22, 16, 30, 0, 123_000_000, ZoneOffset.ofHours(8)))
                .build();
    }

    @Test
    void firstRecord_isGenesisBlock() {
        InMemoryAuditDao dao = new InMemoryAuditDao();
        AuditWriter writer = new AuditWriter(dao, new NoopDetailCipher(), ZONE);

        AuditLog first = writer.append(sampleEvent(1));

        assertEquals(1L, first.getSeq(), "首条 seq 必须是 1");
        assertEquals("0".repeat(64), first.getPrevHash(), "首条 prev_hash 必须是创世 hash（64 个 '0'）");
        assertEquals(CanonicalJson.GENESIS_HASH, first.getPrevHash());
    }

    @Test
    void sequentialWrites_chainLinksCorrectly() {
        InMemoryAuditDao dao = new InMemoryAuditDao();
        AuditWriter writer = new AuditWriter(dao, new NoopDetailCipher(), ZONE);

        AuditLog prev = null;
        for (int i = 1; i <= 10; i++) {
            AuditLog log = writer.append(sampleEvent(i));
            assertEquals(i, log.getSeq());
            if (prev != null) {
                assertEquals(prev.getHash(), log.getPrevHash(), "每条 prev_hash 必须衔接上一条 hash");
            }
            prev = log;
        }
        assertEquals(10, dao.size());
    }

    @Test
    void concurrent100Appends_seqContiguousAndChainVerifies() throws Exception {
        InMemoryAuditDao dao = new InMemoryAuditDao();
        AuditWriter writer = new AuditWriter(dao, new NoopDetailCipher(), ZONE);

        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<AuditLog>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final long n = i;
            futures.add(pool.submit((Callable<AuditLog>) () -> {
                startGate.await(); // 全员就绪后同时起跑，最大化竞争
                return writer.append(sampleEvent(n));
            }));
        }
        startGate.countDown();
        List<AuditLog> written = new ArrayList<>();
        for (Future<AuditLog> f : futures) {
            written.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 断言 1：写入 100 条
        assertEquals(threads, dao.size(), "并发写入后必须有 100 条记录");
        assertEquals(threads, written.size());

        // 断言 2：seq 1~100 连续无空洞（B-2 验收标准）
        for (long seq = 1; seq <= threads; seq++) {
            assertTrue(dao.get(seq) != null, "seq=" + seq + " 缺失（有空洞）");
        }
        // 断言 3：written 返回的 seq 两两不重
        assertEquals(threads, written.stream().map(AuditLog::getSeq).distinct().count(),
                "并发下 seq 出现重复（行锁未串行化）");

        // 断言 4：链校验通过（从创世块开始）
        ChainVerifyResult result = new ChainVerifier(dao, new NoopDetailCipher(), ZONE).verify(1, threads);
        assertTrue(result.isOk(), "100 线程并发写入后链校验必须通过: " + result);
        assertEquals(threads, result.getCheckedCount());
    }

    @Test
    void createdAtNormalizedToWriterZone_hashRoundTrips() {
        // 事件用 UTC 偏移创建，写入方归一化到 Asia/Shanghai；校验方用同一时区还原 → 哈希一致
        InMemoryAuditDao dao = new InMemoryAuditDao();
        AuditWriter writer = new AuditWriter(dao, new NoopDetailCipher(), ZONE);

        AuditEvent utcEvent = AuditEvent.builder()
                .actorId(1L)
                .actorName("utc-user")
                .actorRole("DEV")
                .action("LOGIN")
                .targetType("SESSION")
                .result("SUCCESS")
                .traceId("trc-utc")
                .createdAt(OffsetDateTime.of(2026, 11, 22, 8, 30, 0, 123_000_000, ZoneOffset.UTC)) // = 16:30+08
                .build();
        writer.append(utcEvent);

        ChainVerifyResult result = new ChainVerifier(dao, new NoopDetailCipher(), ZONE).verify(1, 1);
        assertTrue(result.isOk(), "跨时区事件的哈希应按写入时区归一化后往返一致: " + result);
    }

    @Test
    void nullDetail_storedAsNullAndHashesOverNull() {
        InMemoryAuditDao dao = new InMemoryAuditDao();
        AuditWriter writer = new AuditWriter(dao, new NoopDetailCipher(), ZONE);

        AuditEvent noDetail = AuditEvent.builder()
                .actorId(2L)
                .actorName("bare")
                .actorRole("DEV")
                .action("LOGIN")
                .targetType("SESSION")
                .result("SUCCESS")
                .traceId("trc-bare")
                .createdAt(OffsetDateTime.of(2026, 11, 22, 16, 30, 0, 0, ZoneOffset.ofHours(8)))
                .build();
        AuditLog log = writer.append(noDetail);

        assertNull(log.getDetail(), "detailPlaintext 为 null 时 detail 落 null");
        ChainVerifyResult result = new ChainVerifier(dao, new NoopDetailCipher(), ZONE).verify(1, 1);
        assertTrue(result.isOk(), "null detail 参与哈希为 JSON null，校验必须通过: " + result);
    }
}
