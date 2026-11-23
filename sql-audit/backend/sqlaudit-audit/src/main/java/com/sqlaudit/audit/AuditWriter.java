package com.sqlaudit.audit;

import com.sqlaudit.common.audit.CanonicalJson;
import com.sqlaudit.common.model.AuditEvent;
import com.sqlaudit.storage.dao.AuditDao;
import com.sqlaudit.storage.model.AuditLog;
import com.sqlaudit.storage.model.ChainHead;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 审计日志单写入点（README §4.1 不可篡改设计）。
 *
 * <p>写入流程（单事务）：</p>
 * <ol>
 *   <li>{@link AuditDao#lockChainHead()}（{@code SELECT ... WHERE id=1 FOR UPDATE}）
 *       取 last_seq / last_hash，行锁天然把并发写串行化；</li>
 *   <li>seq = last_seq + 1（绝不用 AUTO_INCREMENT id 串链）；</li>
 *   <li>{@link CanonicalJson#computeHash(String, long, AuditEvent)} 计算本条 hash
 *       —— 哈希算 <strong>明文</strong>（detailPlaintext 参与哈希），密文只是存储形态；</li>
 *   <li>组装 {@link AuditLog}（prev_hash / hash / seq 填好，detail 经
 *       {@link DetailCipher#encrypt(String)} 加密）后 insert；</li>
 *   <li>{@link AuditDao#updateChainHead(long, String)} 推进链头。</li>
 * </ol>
 *
 * <p><strong>事务边界由调用方保证</strong>（未来的 Spring {@code @Transactional} 包裹
 * 本方法）：lockChainHead 的行锁只有在一个事务内才存活到 commit。本类只按顺序调
 * DAO 方法，不开事务、不捕获 SQL 异常。</p>
 *
 * <p>created_at 口径：哈希与落库统一用注入的 {@code zone}（生产为平台统一时区）。
 * 写入时把事件的 createdAt 归一化到该时区后参与哈希，落库存归一化后的
 * {@link LocalDateTime}（DATETIME(3) 无时区）；校验时用同一时区还原
 * （见 {@link ChainVerifier}），保证哈希往返一致。</p>
 */
public class AuditWriter {

    private final AuditDao dao;
    private final DetailCipher cipher;
    private final ZoneId zone;

    /**
     * @param dao    审计 DAO（lockChainHead 必须在调用方事务内执行）
     * @param cipher detail 加密器（测试用 {@link NoopDetailCipher}，生产注入 AES-GCM 实现）
     * @param zone   created_at 统一时区（哈希与 DATETIME 存取共用，须与 {@link ChainVerifier} 一致）
     */
    public AuditWriter(AuditDao dao, DetailCipher cipher, ZoneId zone) {
        this.dao = dao;
        this.cipher = cipher;
        this.zone = zone;
    }

    /**
     * 追加一条审计事件并推进链头。
     *
     * <p>事件自带的 seq 被忽略（一律由 chain_head 行锁内分配）；detailPlaintext
     * 为 null 时不加密、detail 落 null。返回写入的 {@link AuditLog}（含分配的
     * seq / prev_hash / hash 与密文 detail）。</p>
     *
     * <p>必须在调用方事务内执行，否则行锁提前释放、并发写可能产生重复 seq。</p>
     *
     * @param event 审计事件（createdAt 建议带明确偏移；内部会归一化到构造时区）
     * @return 已写入的审计日志
     */
    public AuditLog append(AuditEvent event) {
        ChainHead head = dao.lockChainHead();
        long seq = head.getLastSeq() + 1;
        String prevHash = head.getLastHash();

        // 重建事件：写入分配的 seq + created_at 归一化到统一时区（哈希对象 = 校验对象）
        AuditEvent normalized = normalize(event, seq);
        String hash = CanonicalJson.computeHash(prevHash, seq, normalized);

        AuditLog log = toAuditLog(normalized, prevHash, hash);
        dao.insert(log);
        dao.updateChainHead(seq, hash);
        return log;
    }

    private AuditEvent normalize(AuditEvent event, long seq) {
        OffsetDateTime createdAt = event.getCreatedAt().atZoneSameInstant(zone).toOffsetDateTime();
        // 关键：截断到毫秒。hash 链把 createdAt 纳入 canonical JSON，但落库 DATETIME(3)
        // 只存毫秒（纳秒被截断）——若 append 用纳秒、verify 重算用毫秒，hash 必然断裂
        // （MySQL 存储层暴露，内存 fake 不截断所以未现）。截断后 append/verify 精度一致。
        createdAt = createdAt.withNano(createdAt.getNano() / 1_000_000 * 1_000_000);
        return AuditEvent.builder()
                .seq(seq)
                .actorId(event.getActorId())
                .actorName(event.getActorName())
                .actorRole(event.getActorRole())
                .action(event.getAction())
                .targetType(event.getTargetType())
                .targetId(event.getTargetId())
                .instanceId(event.getInstanceId())
                .detailPlaintext(event.getDetailPlaintext())
                .result(event.getResult())
                .clientIp(event.getClientIp())
                .userAgent(event.getUserAgent())
                .traceId(event.getTraceId())
                .createdAt(createdAt)
                .build();
    }

    private AuditLog toAuditLog(AuditEvent event, String prevHash, String hash) {
        AuditLog log = new AuditLog();
        log.setSeq(event.getSeq());
        log.setActorId(event.getActorId());
        log.setActorName(event.getActorName());
        log.setActorRole(event.getActorRole());
        log.setAction(event.getAction());
        log.setTargetType(event.getTargetType());
        log.setTargetId(event.getTargetId());
        log.setInstanceId(event.getInstanceId());
        log.setDetail(event.getDetailPlaintext() == null ? null : cipher.encrypt(event.getDetailPlaintext()));
        log.setResult(event.getResult());
        log.setClientIp(event.getClientIp());
        log.setUserAgent(event.getUserAgent());
        log.setTraceId(event.getTraceId());
        log.setPrevHash(prevHash);
        log.setHash(hash);
        log.setCreatedAt(event.getCreatedAt().toLocalDateTime());
        return log;
    }
}
