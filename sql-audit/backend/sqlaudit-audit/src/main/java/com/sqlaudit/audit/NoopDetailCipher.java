package com.sqlaudit.audit;

import java.nio.charset.StandardCharsets;

/**
 * {@link DetailCipher} 的恒等实现（不加密），仅测试与本地开发用。
 *
 * <p>生产环境必须注入 AES-GCM 实现（auth 模块提供），否则 detail 明文落库，
 * 违反 README §4.1「detail 加密存储」的要求。</p>
 */
public final class NoopDetailCipher implements DetailCipher {

    @Override
    public byte[] encrypt(String plaintext) {
        return plaintext.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String decrypt(byte[] ciphertext) {
        return new String(ciphertext, StandardCharsets.UTF_8);
    }
}
