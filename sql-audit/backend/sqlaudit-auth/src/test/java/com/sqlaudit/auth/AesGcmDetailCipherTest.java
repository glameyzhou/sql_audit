package com.sqlaudit.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 审计明细 AES-GCM 加解密单测。
 *
 * <p>覆盖：往返一致 / nonce 随机（同明文两次密文不同）/ 密文篡改拒解 / 长度非法拒解 /
 * 空明文拒加密 / 中文 UTF-8 往返。</p>
 */
class AesGcmDetailCipherTest {

    private final AesGcmDetailCipher cipher = new AesGcmDetailCipher();

    @Test
    void encryptDecrypt_roundtrip() {
        String plaintext = "{\"rollbackId\":7,\"stmtOrder\":0,\"sql\":\"UPDATE t SET a='x' WHERE id=1\"}";
        byte[] encrypted = cipher.encrypt(plaintext);
        assertEquals(plaintext, cipher.decrypt(encrypted));
    }

    @Test
    void encrypt_randomNonce_producesDifferentCiphertext() {
        byte[] first = cipher.encrypt("same-plaintext");
        byte[] second = cipher.encrypt("same-plaintext");
        assertNotEquals(java.util.Arrays.hashCode(first), java.util.Arrays.hashCode(second),
                "nonce 随机，同明文两次密文应不同（密文不同不破 hash 链的前提）");
    }

    @Test
    void decrypt_tamperedCiphertext_throws() {
        byte[] encrypted = cipher.encrypt("tamper-me");
        encrypted[encrypted.length - 1] ^= 0x01; // 翻转 tag 最后一个字节
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(encrypted),
                "GCM tag 校验失败必须拒解");
    }

    @Test
    void decrypt_tooShortCiphertext_throws() {
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt(new byte[10]),
                "长度 < iv+tag 必须拒解");
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt(null),
                "null 密文必须拒解");
    }

    @Test
    void encrypt_nullPlaintext_throws() {
        assertThrows(IllegalArgumentException.class, () -> cipher.encrypt(null));
    }

    @Test
    void encryptDecrypt_utf8Chinese_roundtrip() {
        String plaintext = "工单 #42 回滚执行：前镜像 hash 不匹配，已拒绝";
        assertEquals(plaintext, cipher.decrypt(cipher.encrypt(plaintext)));
    }

    @Test
    void ciphertextLayout_ivPrefixThenBody() {
        // 密文格式约定：iv(12B) || ciphertext+tag；长度恒为 12 + 明文字节数 + 16(tag)
        byte[] encrypted = cipher.encrypt("layout-check");
        assertEquals(12 + "layout-check".getBytes(StandardCharsets.UTF_8).length + 16,
                encrypted.length, "密文总长应为 iv + 明文 + GCM tag");
        byte[] another = cipher.encrypt("layout-check");
        assertNotEquals(java.util.Arrays.hashCode(java.util.Arrays.copyOf(encrypted, 12)),
                java.util.Arrays.hashCode(java.util.Arrays.copyOf(another, 12)),
                "前 12 字节 iv 应随 nonce 变化");
    }
}
