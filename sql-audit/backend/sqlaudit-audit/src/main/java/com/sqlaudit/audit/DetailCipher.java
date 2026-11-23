package com.sqlaudit.audit;

/**
 * detail 字段的加密器（README §4.1 第 2 点：哈希算明文，不算密文）。
 *
 * <p>哈希在加密前的明文上计算，密文只是存储形态。这样密钥轮换或重新加密
 * （每次 AES-GCM nonce 不同 → 密文不同）不会破坏 hash 链；校验链时需先
 * {@link #decrypt(byte[])} 还原明文再重算哈希。</p>
 *
 * <p>真实现（AES-GCM，属 auth 模块范围）由构造注入；测试用
 * {@link NoopDetailCipher} 恒等实现。</p>
 */
public interface DetailCipher {

    /**
     * 加密明文 detail。
     *
     * @param plaintext 明文（可为任意 UTF-8 字符串，非 null）
     * @return 密文字节（落 {@code audit_log.detail VARBINARY}）
     */
    byte[] encrypt(String plaintext);

    /**
     * 解密密文 detail，还原明文（链校验用）。
     *
     * @param ciphertext {@link #encrypt(String)} 的返回值
     * @return 明文
     */
    String decrypt(byte[] ciphertext);
}
