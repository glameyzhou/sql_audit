package com.sqlaudit.common.audit;

import com.sqlaudit.common.model.AuditEvent;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AuditEvent canonical JSON 黄金用例（设计文档 §7.3 / W0-2 验收）。
 *
 * <p>目的：锁死 canonical 字段序、命名、日期格式、NULL 规则与 hash 公式。
 * 后人一旦重构改了字段序或格式，本测试必须变红。</p>
 */
class AuditEventCanonicalTest {

    /** 固定输入：所有字段取非空、非默认值，时间含 3 位毫秒 + 时区偏移。 */
    private static AuditEvent sampleEvent() {
        return AuditEvent.builder()
                .seq(1L)
                .actorId(1001L)
                .actorName("文曜")
                .actorRole("DBA")
                .action("UNIT_EXEC")
                .targetType("TICKET_UNIT")
                .targetId("42")
                .instanceId(7L)
                .detailPlaintext("UPDATE t SET a=1 WHERE id=42")
                .result("SUCCESS")
                .clientIp("10.0.0.8")
                .userAgent("Mozilla/5.0")
                .traceId("trc-20261122-0001")
                .createdAt(OffsetDateTime.of(2026, 11, 22, 16, 30, 0, 123_000_000, ZoneOffset.ofHours(8)))
                .build();
    }

    @Test
    void canonicalJson_isExactAndOrdered() {
        // 字段序 = §7.3 定义的固定顺序；无缩进、无空格；数字不加引号；
        // created_at 用 yyyy-MM-dd'T'HH:mm:ss.SSSXXX，毫秒满 3 位
        String expected = "{\"seq\":1,\"actor_id\":1001,\"actor_name\":\"文曜\","
                + "\"actor_role\":\"DBA\",\"action\":\"UNIT_EXEC\",\"target_type\":\"TICKET_UNIT\","
                + "\"target_id\":\"42\",\"instance_id\":7,\"detail_plaintext\":\"UPDATE t SET a=1 WHERE id=42\","
                + "\"result\":\"SUCCESS\",\"client_ip\":\"10.0.0.8\",\"user_agent\":\"Mozilla/5.0\","
                + "\"trace_id\":\"trc-20261122-0001\",\"created_at\":\"2026-11-22T16:30:00.123+08:00\"}";

        assertEquals(expected, CanonicalJson.toJson(sampleEvent()));
    }

    @Test
    void nullFields_serializeAsJsonNull_notOmitted() {
        AuditEvent event = AuditEvent.builder()
                .seq(2L)
                .actorId(1002L)
                .actorName("user")
                .actorRole("DEV")
                .action("LOGIN")
                .targetType("SESSION")
                // targetId / instanceId / detailPlaintext / clientIp / userAgent 留 null
                .result("SUCCESS")
                .traceId("trc-2")
                .createdAt(OffsetDateTime.of(2026, 11, 22, 16, 30, 0, 0, ZoneOffset.ofHours(8)))
                .build();

        String json = CanonicalJson.toJson(event);

        // NULL 必须是 JSON null，且键不省略（§7.3 第 2 点）
        assertTrue(json.contains("\"target_id\":null"), "target_id 应为 null 而非省略");
        assertTrue(json.contains("\"instance_id\":null"), "instance_id 应为 null 而非省略");
        assertTrue(json.contains("\"detail_plaintext\":null"), "detail_plaintext 应为 null 而非省略");
    }

    @Test
    void computeHash_isStableGoldenValue() {
        // 黄金 hash：固定输入 → 固定 SHA256。任何字段序/命名/日期格式/NULL 规则的改动
        // 都会让此断言变红——这正是 hash 链契约的守卫（§7.3 / W0-2 验收）。
        String hash = CanonicalJson.computeHash(CanonicalJson.GENESIS_HASH, 1L, sampleEvent());

        assertEquals("f4460841bcbb29ae85540487181623ebf2bfe15a1670c621322cf1232240d54e", hash,
                "黄金 hash 不一致：canonical 序列化被改动，历史 hash 链将断裂");

        // 确定性：同一输入两次结果一致
        assertEquals(hash, CanonicalJson.computeHash(CanonicalJson.GENESIS_HASH, 1L, sampleEvent()));
    }

    @Test
    void genesisHash_isSixtyFourZeros() {
        assertEquals("0".repeat(64), CanonicalJson.GENESIS_HASH);
    }
}
