package com.sqlaudit.audit;

import com.sqlaudit.common.audit.CanonicalJson;
import com.sqlaudit.common.model.AuditEvent;
import com.sqlaudit.storage.dao.AuditDao;
import com.sqlaudit.storage.model.AuditLog;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * hash 链完整性校验（设计文档 §7.3「定期校验任务比对链完整性，断裂即告警」）。
 *
 * <p>逐条校验两件事：</p>
 * <ol>
 *   <li><strong>衔接</strong>：第一条（fromSeq=1 时）的 prev_hash 必须等于创世
 *       hash（64 个 '0'）；fromSeq&gt;1 时取 seq=fromSeq-1 那条的 hash 作为期望
 *       prev_hash；其后每条的 prev_hash 必须等于上一条的 hash（中间记录被删即断裂）；</li>
 *   <li><strong>重算</strong>：用 {@link DetailCipher#decrypt(byte[])} 解密 detail
 *       还原明文，从 AuditLog 各列重建 {@link AuditEvent}（created_at 按统一时区
 *       还原为 OffsetDateTime），重算
 *       {@code SHA256(prev_hash || seq || canonical_json)} 与存储的 hash 比对。</li>
 * </ol>
 *
 * <p>哈希算明文不算密文（§7.3 第 2 点），所以校验需要解密权限——这一点在运维
 * SOP 中写明。校验不修改任何数据，结果以 {@link ChainVerifyResult} 返回、不抛
 * 业务异常（区间内记录缺失/解密失败等均记为断裂）。</p>
 *
 * <p>局限（口径诚实）：链尾之后的记录被整段删除无法从区间内部察觉，需配合
 * §7.3 的「链头外置」WORM 每日锚点比对。</p>
 */
public class ChainVerifier {

    private final AuditDao dao;
    private final DetailCipher cipher;
    private final ZoneId zone;

    /**
     * @param dao    审计 DAO（只读使用 listBySeqRange，不碰 chain_head 行锁）
     * @param cipher detail 解密器（须与写入时 {@link AuditWriter} 用同一密钥体系）
     * @param zone   created_at 统一时区，必须与 {@link AuditWriter} 构造时一致，否则哈希必然不一致
     */
    public ChainVerifier(AuditDao dao, DetailCipher cipher, ZoneId zone) {
        this.dao = dao;
        this.cipher = cipher;
        this.zone = zone;
    }

    /**
     * 校验 seq ∈ [fromSeq, toSeq] 的链段。
     *
     * @param fromSeq 起始 seq（=1 时校验创世衔接；&gt;1 时用前一条的 hash 做衔接锚点）
     * @param toSeq   结束 seq（含）
     * @return 校验结果（ok / 首个断裂 seq / 原因），不抛异常
     */
    public ChainVerifyResult verify(long fromSeq, long toSeq) {
        if (fromSeq < 1 || toSeq < fromSeq) {
            return ChainVerifyResult.breakAt(0, fromSeq, "非法区间 [" + fromSeq + ", " + toSeq + "]");
        }

        // fromSeq > 1 时多取一条（fromSeq-1）做衔接锚点
        List<AuditLog> fetched = dao.listBySeqRange(fromSeq > 1 ? fromSeq - 1 : 1, toSeq);
        fetched.sort(Comparator.comparingLong(AuditLog::getSeq));

        String expectedPrev = fromSeq == 1 ? CanonicalJson.GENESIS_HASH : null;
        List<AuditLog> targets = new ArrayList<>();
        for (AuditLog log : fetched) {
            long seq = log.getSeq();
            if (seq < fromSeq) {
                if (seq == fromSeq - 1) {
                    expectedPrev = log.getHash(); // 锚点：只取 hash
                }
                continue;
            }
            targets.add(log);
        }

        long checked = 0;
        String prevHash = expectedPrev; // null = 锚点缺失（前一条不在库中），首条只能校验重算
        for (AuditLog log : targets) {
            checked++;
            // ① 衔接
            if (prevHash != null && !prevHash.equals(log.getPrevHash())) {
                return ChainVerifyResult.breakAt(checked, log.getSeq(),
                        "prev_hash 与上一条 hash 不衔接（期望 " + prevHash + "，实际 " + log.getPrevHash() + "）");
            }
            // ② 重算（detail 密文 → 明文 → 重建事件 → 重算 hash）
            try {
                String recomputed = CanonicalJson.computeHash(log.getPrevHash(), log.getSeq(), rebuildEvent(log));
                if (!recomputed.equals(log.getHash())) {
                    return ChainVerifyResult.breakAt(checked, log.getSeq(),
                            "hash 重算不一致（存储 " + log.getHash() + "，重算 " + recomputed + "）");
                }
            } catch (Exception e) {
                return ChainVerifyResult.breakAt(checked, log.getSeq(), "记录无法重算（detail 解密失败等）：" + e.getMessage());
            }
            prevHash = log.getHash();
        }
        return ChainVerifyResult.ok(checked);
    }

    /** 从 AuditLog 各列 + 解密后的 detail 重建 AuditEvent（与写入时哈希的对象对齐）。 */
    private AuditEvent rebuildEvent(AuditLog log) {
        String detailPlaintext = log.getDetail() == null ? null : cipher.decrypt(log.getDetail());
        return AuditEvent.builder()
                .seq(log.getSeq())
                .actorId(log.getActorId())
                .actorName(log.getActorName())
                .actorRole(log.getActorRole())
                .action(log.getAction())
                .targetType(log.getTargetType())
                .targetId(log.getTargetId())
                .instanceId(log.getInstanceId())
                .detailPlaintext(detailPlaintext)
                .result(log.getResult())
                .clientIp(log.getClientIp())
                .userAgent(log.getUserAgent())
                .traceId(log.getTraceId())
                .createdAt(ZonedDateTime.of(log.getCreatedAt(), zone).toOffsetDateTime())
                .build();
    }
}
