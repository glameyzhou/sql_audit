package com.sqlaudit.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 生产库凭据 AES-256-GCM 加解密（对应 {@code t_credential.password_enc}）。
 *
 * <p>密文格式：{@code <ivBase64>.<ciphertextBase64>}，随机 12 字节 nonce，128-bit GCM tag。
 * 密钥 32 字节，读取顺序：环境变量 {@code SQLAUDIT_CREDENTIAL_KEY}（base64 或 UTF-8 补齐）→
 * 本地开发默认密钥。</p>
 *
 * <p><strong>生产必须覆盖密钥</strong>：本地默认密钥是公开的开发常量，仅用于本地联调与集成
 * 测试，绝不可用于生产——否则任何持有默认密钥的人都能解密线上凭据。部署时通过
 * {@code SQLAUDIT_CREDENTIAL_KEY}（base64 编码的 32 字节随机密钥）注入。</p>
 */
public final class CredentialCrypto {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String ENV_KEY = "SQLAUDIT_CREDENTIAL_KEY";
    private static final String SEPARATOR = ".";

    /** 本地开发默认密钥（32 字节）。仅开发/测试用，生产必须经环境变量覆盖。 */
    private static final String DEV_KEY = "SQLAUDIT-LOCAL-DEV-KEY-32B-01234";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] KEY = AesGcmKeyResolver.resolve(ENV_KEY, DEV_KEY);

    private CredentialCrypto() {
    }

    /** 加密明文为 {@code <ivBase64>.<ciphertextBase64>}。 */
    public static String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new CredentialCryptoException("明文为空");
        }
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        byte[] ciphertext = doEncrypt(plaintext.getBytes(StandardCharsets.UTF_8), iv, KEY);
        return Base64.getEncoder().encodeToString(iv)
                + SEPARATOR
                + Base64.getEncoder().encodeToString(ciphertext);
    }

    /** 解密 {@code <ivBase64>.<ciphertextBase64>} 为明文；密文被篡改或密钥不匹配时抛异常。 */
    public static String decrypt(String encrypted) {
        return decrypt(encrypted, KEY);
    }

    /** 指定密钥解密（测试错钥路径用；与 {@link #decrypt(String)} 同语义）。 */
    static String decrypt(String encrypted, byte[] key) {
        if (encrypted == null || encrypted.isEmpty()) {
            throw new CredentialCryptoException("密文为空");
        }
        int dot = encrypted.indexOf(SEPARATOR);
        if (dot <= 0 || dot == encrypted.length() - 1) {
            throw new CredentialCryptoException("密文格式非法（应为 ivBase64.ciphertextBase64）");
        }
        byte[] iv;
        byte[] ciphertext;
        try {
            iv = Base64.getDecoder().decode(encrypted.substring(0, dot));
            ciphertext = Base64.getDecoder().decode(encrypted.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new CredentialCryptoException("密文 Base64 解码失败", e);
        }
        if (iv.length != IV_BYTES) {
            throw new CredentialCryptoException("IV 长度非法: " + iv.length);
        }
        return new String(doDecrypt(ciphertext, iv, key), StandardCharsets.UTF_8);
    }

    private static byte[] doEncrypt(byte[] plaintext, byte[] iv, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM),
                    new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new CredentialCryptoException("AES-GCM 加密失败", e);
        }
    }

    private static byte[] doDecrypt(byte[] ciphertext, byte[] iv, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM),
                    new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new CredentialCryptoException("AES-GCM 解密失败（密文被篡改或密钥不匹配）", e);
        }
    }

}
