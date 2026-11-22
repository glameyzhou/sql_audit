package com.sqlaudit.common.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialCryptoTest {

    @Test
    void encryptThenDecrypt_roundTrips() {
        String plaintext = "P@ssw0rd!生产库凭据";
        String encrypted = CredentialCrypto.encrypt(plaintext);

        assertTrue(encrypted.contains("."), "密文应为 ivBase64.ciphertextBase64: " + encrypted);
        assertEquals(plaintext, CredentialCrypto.decrypt(encrypted));
    }

    @Test
    void encrypt_usesRandomIv_eachCallDiffers() {
        String plaintext = "same-password";
        String a = CredentialCrypto.encrypt(plaintext);
        String b = CredentialCrypto.encrypt(plaintext);

        assertNotEquals(a, b, "随机 IV 使同一明文两次加密结果不同");
        assertEquals(plaintext, CredentialCrypto.decrypt(a));
        assertEquals(plaintext, CredentialCrypto.decrypt(b));
    }

    @Test
    void decrypt_tamperedCiphertext_throws() {
        String encrypted = CredentialCrypto.encrypt("secret");
        int dot = encrypted.indexOf('.');
        // 翻转密文首字节的 base64 字符，破坏 GCM 完整性校验
        char flipped = encrypted.charAt(dot + 1) == 'A' ? 'B' : 'A';
        String tampered = encrypted.substring(0, dot + 1)
                + flipped + encrypted.substring(dot + 2);

        assertThrows(CredentialCryptoException.class, () -> CredentialCrypto.decrypt(tampered));
    }

    @Test
    void decrypt_wrongKey_throws() {
        String encrypted = CredentialCrypto.encrypt("secret");
        byte[] wrongKey = new byte[32];
        java.util.Arrays.fill(wrongKey, (byte) 0x7f);

        assertThrows(CredentialCryptoException.class,
                () -> CredentialCrypto.decrypt(encrypted, wrongKey));
    }

    @Test
    void decrypt_malformedFormat_throws() {
        assertThrows(CredentialCryptoException.class, () -> CredentialCrypto.decrypt("no-separator"));
        assertThrows(CredentialCryptoException.class,
                () -> CredentialCrypto.decrypt("not-base64!!.also-not-base64"));
    }

    @Test
    void encrypt_null_throws() {
        assertThrows(CredentialCryptoException.class, () -> CredentialCrypto.encrypt(null));
    }

    @Test
    void ivLength_is12Bytes() {
        String encrypted = CredentialCrypto.encrypt("x");
        byte[] iv = Base64.getDecoder().decode(encrypted.substring(0, encrypted.indexOf('.')));
        assertEquals(12, iv.length, "IV 应为 12 字节（96-bit GCM nonce）");
    }

    @Test
    void defaultKeyIs32Bytes_utf8() {
        // 默认开发密钥必须恰好 32 字节（AES-256 前提）
        byte[] key = "SQLAUDIT-LOCAL-DEV-KEY-32B-01234".getBytes(StandardCharsets.UTF_8);
        assertEquals(32, key.length);
    }
}
