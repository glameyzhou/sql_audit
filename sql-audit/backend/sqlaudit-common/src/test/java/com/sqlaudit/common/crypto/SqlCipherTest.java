package com.sqlaudit.common.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlCipher} 契约测试：SQL 明文绝不落库，密文格式 {@code iv || 密文+tag}。
 */
class SqlCipherTest {

    private final SqlCipher cipher = new SqlCipher();

    @Test
    void encryptThenDecrypt_往返一致() {
        String sql = "UPDATE t_order SET status = 'PAID' WHERE id = 42";
        byte[] encrypted = cipher.encrypt(sql);

        assertEquals(sql, cipher.decrypt(encrypted));
        // iv(12) + 明文长度 + tag(16)
        assertEquals(12 + sql.getBytes(StandardCharsets.UTF_8).length + 16, encrypted.length);
    }

    @Test
    void encrypt_密文不含明文片段() {
        String sql = "DELETE FROM t_order WHERE id = 7";
        byte[] encrypted = cipher.encrypt(sql);

        String raw = new String(encrypted, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("DELETE"), "密文里不得出现明文片段");
        assertFalse(raw.contains("t_order"), "密文里不得出现明文片段");
    }

    @Test
    void encrypt_随机nonce_同一明文两次结果不同() {
        String sql = "SELECT 1";
        byte[] a = cipher.encrypt(sql);
        byte[] b = cipher.encrypt(sql);

        assertNotEquals(Arrays.toString(a), Arrays.toString(b), "随机 nonce 使同一明文两次密文不同");
        assertFalse(Arrays.equals(Arrays.copyOf(a, 12), Arrays.copyOf(b, 12)), "前 12 字节 iv 应不同");
        assertEquals(sql, cipher.decrypt(a));
        assertEquals(sql, cipher.decrypt(b));
    }

    @Test
    void decrypt_密文被篡改_抛异常() {
        byte[] encrypted = cipher.encrypt("UPDATE t_order SET amount = 1");
        byte[] tampered = encrypted.clone();
        tampered[tampered.length - 1] ^= 0x01; // 翻转 tag 末位，破坏 GCM 完整性校验

        assertThrows(CredentialCryptoException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void decrypt_换密钥_抛异常() {
        byte[] encrypted = cipher.encrypt("UPDATE t_order SET amount = 1");
        SqlCipher other = new SqlCipher("another-32-byte-key-0123456789ab".getBytes(StandardCharsets.UTF_8));

        assertThrows(CredentialCryptoException.class, () -> other.decrypt(encrypted));
    }

    @Test
    void decrypt_长度不足_抛异常() {
        assertThrows(CredentialCryptoException.class, () -> cipher.decrypt(new byte[11]));
        assertThrows(CredentialCryptoException.class, () -> cipher.decrypt(new byte[27]));
    }

    @Test
    void 中文与多行SQL_UTF8往返一致() {
        String sql = "-- 订单状态修复（2026-11-23）\n"
                + "UPDATE `t_order` SET remark = '已退款：用户申请' WHERE id IN (1, 2, 3);";
        assertEquals(sql, cipher.decrypt(cipher.encrypt(sql)));
    }

    @Test
    void 空串与null_透传语义() {
        assertNull(cipher.encrypt(null), "null 明文透传为 null（对应可空列）");
        assertNull(cipher.decrypt(null), "null 密文透传为 null");

        byte[] empty = cipher.encrypt("");
        assertEquals(28, empty.length, "空串密文 = iv(12) + tag(16)");
        assertEquals("", cipher.decrypt(empty));
    }

    @Test
    void 大SQL文本_1MiB往返一致() {
        String sql = "INSERT INTO t_big VALUES " + "('x'),".repeat(100_000);
        byte[] encrypted = cipher.encrypt(sql);
        assertTrue(encrypted.length > 600_000, "大文本不应被截断");
        assertEquals(sql, cipher.decrypt(encrypted));
    }

    @Test
    void 自带密钥构造_同密钥可互相解密_非法长度拒绝() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        SqlCipher a = new SqlCipher(key);
        SqlCipher b = new SqlCipher(key);
        assertEquals("ok", b.decrypt(a.encrypt("ok")));

        assertThrows(CredentialCryptoException.class, () -> new SqlCipher(new byte[7]));
        assertThrows(CredentialCryptoException.class, () -> new SqlCipher(null));
    }

    @Test
    void 密钥副本隔离_外部修改入参数组不影响实例() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        SqlCipher a = new SqlCipher(key);
        byte[] encrypted = a.encrypt("ok");
        Arrays.fill(key, (byte) 0);

        assertEquals("ok", a.decrypt(encrypted), "构造时应拷贝密钥，不受外部数组改写影响");
        assertArrayEquals(new byte[32], key);
    }
}
