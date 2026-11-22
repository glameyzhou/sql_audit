package com.sqlaudit.auth;

import com.sqlaudit.audit.DetailCipher;
import com.sqlaudit.common.crypto.AesGcmKeyResolver;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 审计明细 AES-256-GCM 加解密（设计文档 §7.3：detail 密文落库、哈希算明文）。
 *
 * <p>密文格式：{@code iv(12B) || ciphertext+tag(128-bit)}，随机 nonce 每次加密都不同。
 * 密钥 32 字节，读取顺序：环境变量 {@code SQLAUDIT_DETAIL_KEY}（base64 或 UTF-8 补齐）→
 * 本地开发默认密钥。与 {@link com.sqlaudit.common.crypto.CredentialCrypto}（生产库凭据）
 * 同模式但密钥隔离：轮换明细密钥不影响凭据，反之亦然。</p>
 *
 * <p>哈希在加密前的明文上计算（{@link com.sqlaudit.audit.AuditWriter}），nonce 不同导致
 * 密文不同也不会破坏审计 hash 链；{@link com.sqlaudit.audit.ChainVerifier} 校验前先
 * {@link #decrypt(byte[])} 还原明文再重算哈希。解密失败（密文被篡改或密钥不匹配）抛
 * {@link IllegalStateException}——链校验把它记为「记录无法重算」的断裂，不中断整段校验。</p>
 *
 * <p><strong>生产必须覆盖密钥</strong>：本地默认密钥是公开的开发常量，仅用于本地联调与
 * 集成测试。部署时通过 {@code SQLAUDIT_DETAIL_KEY}（base64 编码的 32 字节随机密钥）注入。</p>
 */
public final class AesGcmDetailCipher implements DetailCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MIN_CIPHERTEXT_BYTES = IV_BYTES + 16; // iv + 128-bit tag
    private static final String ENV_KEY = "SQLAUDIT_DETAIL_KEY";

    /** 本地开发默认密钥（32 字节）。仅开发/测试用，生产必须经环境变量覆盖。 */
    private static final String DEV_KEY = "SQLAUDIT-LOCAL-DETAIL-KEY-32B-56";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] KEY = AesGcmKeyResolver.resolve(ENV_KEY, DEV_KEY);

    @Override
    public byte[] encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("明文为空");
        }
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, KEY_ALGORITHM),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(ciphertext, 0, out, IV_BYTES, ciphertext.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("审计明细 AES-GCM 加密失败", e);
        }
    }

    @Override
    public String decrypt(byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length < MIN_CIPHERTEXT_BYTES) {
            throw new IllegalArgumentException(
                    "密文长度非法（应 ≥ iv+tag，共 " + MIN_CIPHERTEXT_BYTES + " 字节）: "
                            + (ciphertext == null ? "null" : ciphertext.length));
        }
        byte[] iv = Arrays.copyOfRange(ciphertext, 0, IV_BYTES);
        byte[] body = Arrays.copyOfRange(ciphertext, IV_BYTES, ciphertext.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, KEY_ALGORITHM),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("审计明细 AES-GCM 解密失败（密文被篡改或密钥不匹配）", e);
        }
    }
}
