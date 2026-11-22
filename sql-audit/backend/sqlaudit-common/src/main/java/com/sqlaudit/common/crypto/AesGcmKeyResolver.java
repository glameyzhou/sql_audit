package com.sqlaudit.common.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-GCM 密钥解析公共工具（{@link CredentialCrypto} 与审计明细加密共用同一规则，
 * 避免两处各写一套）。
 *
 * <p>解析顺序：环境变量（base64 编码的 32 字节随机密钥优先，否则按 UTF-8 截断/补零到
 * 32 字节）→ 本地开发默认密钥。开发默认密钥是公开的开发常量，仅用于本地联调与集成
 * 测试，生产必须经环境变量覆盖。</p>
 */
public final class AesGcmKeyResolver {

    private static final int KEY_BYTES = 32;

    private AesGcmKeyResolver() {
    }

    /** 解析 32 字节 AES 密钥。 */
    public static byte[] resolve(String envName, String devKey) {
        String env = System.getenv(envName);
        if (env == null || env.isBlank()) {
            return toKeyBytes(devKey.getBytes(StandardCharsets.UTF_8));
        }
        // 优先按 base64 解析（须恰好 32 字节），否则按 UTF-8 补齐到 32 字节
        byte[] decoded = tryBase64(env);
        if (decoded != null && decoded.length == KEY_BYTES) {
            return decoded;
        }
        return toKeyBytes(env.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] tryBase64(String s) {
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 截断/补零到 32 字节密钥。 */
    private static byte[] toKeyBytes(byte[] raw) {
        byte[] key = new byte[KEY_BYTES];
        Arrays.fill(key, (byte) 0);
        System.arraycopy(raw, 0, key, 0, Math.min(raw.length, key.length));
        return key;
    }
}
