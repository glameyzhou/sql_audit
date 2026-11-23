package com.sqlaudit.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * SQL 文本的 AES-256-GCM 透明加解密（工单原文、执行语句、回滚反向语句与旧值参数）。
 *
 * <p>覆盖列：{@code t_ticket.sql_text}、{@code t_ticket_unit.statement}、
 * {@code t_rollback_statement.reverse_sql} / {@code value_params}——它们都可能含生产数据
 * （回滚旧值就是前镜像），库里不留明文。模型里是明文 {@code String}，加解密只发生在
 * JDBC DAO 边界；{@code statement_hash} 仍按明文计算（不变）。</p>
 *
 * <p>密文格式：{@code iv(12B) || ciphertext+tag}（裸字节，不做 Base64——列是
 * LONGBLOB / BYTEA），随机 12 字节 nonce，128-bit GCM tag。密钥 32 字节，读取顺序：
 * 环境变量 {@code SQLAUDIT_SQL_KEY}（base64 或 UTF-8 补齐，见 {@link AesGcmKeyResolver}）→
 * 本地开发默认密钥。</p>
 *
 * <p><strong>生产必须覆盖密钥</strong>：本地默认密钥是公开的开发常量，仅用于本地联调与集成
 * 测试。与凭据密钥（{@code SQLAUDIT_CREDENTIAL_KEY}）、审计明细密钥
 * （{@code SQLAUDIT_DETAIL_KEY}）相互隔离，任一轮换不影响其他两类数据。
 * <strong>密钥丢失 = 历史工单 SQL 不可读</strong>。</p>
 *
 * <p>实例无状态、线程安全（{@link Cipher} 每次调用新建），作为单例 bean 注入各 JDBC DAO。</p>
 */
public class SqlCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    /** 密文最小长度 = iv(12) + tag(16)（明文可以为空串）。 */
    private static final int MIN_CIPHERTEXT_BYTES = IV_BYTES + TAG_BITS / 8;

    private static final String ENV_KEY = "SQLAUDIT_SQL_KEY";

    /** 本地开发默认密钥（32 字节）。仅开发/测试用，生产必须经环境变量覆盖。 */
    private static final String DEV_KEY = "SQLAUDIT-LOCAL-DEV-SQL-KEY-32B01";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;

    /** 按环境变量 {@code SQLAUDIT_SQL_KEY} 解析密钥（缺省回落本地开发密钥）。 */
    public SqlCipher() {
        this(AesGcmKeyResolver.resolve(ENV_KEY, DEV_KEY));
    }

    /** 指定密钥（测试与密钥轮换用）；长度必须是 16 / 24 / 32 字节。 */
    public SqlCipher(byte[] key) {
        if (key == null || (key.length != 16 && key.length != 24 && key.length != 32)) {
            throw new CredentialCryptoException("AES 密钥长度非法: " + (key == null ? "null" : key.length));
        }
        this.key = key.clone();
    }

    /**
     * 明文 → {@code iv || 密文+tag}；{@code null} 透传为 {@code null}（对应可空列）。
     * 同一明文每次加密结果不同（随机 nonce）。
     */
    public byte[] encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        byte[] ciphertext = doFinal(Cipher.ENCRYPT_MODE, plaintext.getBytes(StandardCharsets.UTF_8), iv);
        byte[] out = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
        return out;
    }

    /**
     * {@code iv || 密文+tag} → 明文；{@code null} 透传为 {@code null}。
     *
     * @throws CredentialCryptoException 长度非法、密文被篡改或密钥不匹配
     */
    public String decrypt(byte[] encrypted) {
        if (encrypted == null) {
            return null;
        }
        if (encrypted.length < MIN_CIPHERTEXT_BYTES) {
            throw new CredentialCryptoException("密文长度非法: " + encrypted.length
                    + "（应 >= iv 12B + tag 16B）");
        }
        byte[] iv = Arrays.copyOfRange(encrypted, 0, IV_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(encrypted, IV_BYTES, encrypted.length);
        return new String(doFinal(Cipher.DECRYPT_MODE, ciphertext, iv), StandardCharsets.UTF_8);
    }

    private byte[] doFinal(int mode, byte[] input, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, new SecretKeySpec(key, KEY_ALGORITHM), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new CredentialCryptoException(mode == Cipher.ENCRYPT_MODE
                    ? "SQL AES-GCM 加密失败"
                    : "SQL AES-GCM 解密失败（密文被篡改或密钥不匹配）", e);
        }
    }
}
