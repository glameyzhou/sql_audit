package com.sqlaudit.common.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sqlaudit.common.model.AuditEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 审计 canonical JSON 序列化 + hash 链计算（设计文档 §7.3）。
 *
 * <p><strong>这是 hash 链的单一实现点</strong>（§7.3 第 3 点建议）：任何字段序、命名、
 * 日期格式、NULL 规则的改动都会改变哈希，必须由黄金用例单测锁死。后人重构时若无意改动，
 * 黄金用例会变红。</p>
 *
 * <p>hash 公式（§7.3）：{@code hash = SHA256(prev_hash || seq || canonical_json(record))}，
 * 其中 {@code ||} 为字符串拼接，{@code prev_hash} 为 64 位 hex，{@code seq} 为十进制数字。</p>
 */
public final class CanonicalJson {

    /** 创世 prev_hash：64 个 '0'，与 chain_head.last_hash 默认值一致 */
    public static final String GENESIS_HASH = "0".repeat(64);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            // 关键：NULL 必须序列化为 JSON null，而不是省略键（§7.3 第 2 点）
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private CanonicalJson() {
    }

    /** 序列化 canonical JSON（无缩进、无多余空格）。 */
    public static String toJson(AuditEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("canonical JSON 序列化失败，审计记录不可落库", e);
        }
    }

    /**
     * 计算本条的 hash。
     *
     * @param prevHash 上一条记录的 hash（64 位 hex）；创世块传 {@link #GENESIS_HASH}
     * @param seq      本条的链序号（十进制，直接字符串拼接）
     * @param event    审计事件（canonical 记录）
     * @return 64 位小写 hex 的 SHA256
     */
    public static String computeHash(String prevHash, long seq, AuditEvent event) {
        String canonical = toJson(event);
        return sha256Hex(prevHash + seq + canonical);
    }

    /** 计算任意字符串的 SHA256（64 位小写 hex）。 */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 算法", e);
        }
    }
}
