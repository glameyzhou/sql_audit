package com.sqlaudit.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * 无依赖 JWT（HS256，HMAC-SHA256）。
 *
 * <p>内部工具不引重依赖，手写最小 JWT：{@code header.payload.signature} 三段 base64url。
 * payload 承载 userId / username / roles（逗号分隔）/ exp。签名密钥走环境变量
 * {@code SQLAUDIT_JWT_SECRET}（生产必须覆盖，默认值仅本地开发）。</p>
 */
public final class JwtService {

    private final byte[] secret;

    public JwtService(String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("JWT secret 过短（≥16 字符），生产必须经 SQLAUDIT_JWT_SECRET 注入");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** 签发 token。roles 逗号分隔；ttlSeconds 过期时长。 */
    public String issue(long userId, String username, List<String> roles, long ttlSeconds) {
        long exp = Instant.now().getEpochSecond() + ttlSeconds;
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = b64("{\"uid\":" + userId + ",\"name\":\"" + username
                + "\",\"roles\":\"" + String.join(",", roles)
                + "\",\"exp\":" + exp + "}");
        String signing = header + "." + payload;
        return signing + "." + b64(sign(signing));
    }

    /** 校验并解析 token；签名不符 / 过期 / 格式错 → {@code null}。 */
    public Claims verify(String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        String signing = parts[0] + "." + parts[1];
        String expected = b64(sign(signing));
        if (!constantTimeEquals(expected, parts[2])) {
            return null;
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            long uid = extractLong(payload, "\"uid\":");
            String name = extractString(payload, "\"name\":\"");
            String roles = extractString(payload, "\"roles\":\"");
            long exp = extractLong(payload, "\"exp\":");
            if (exp < Instant.now().getEpochSecond()) {
                return null;
            }
            List<String> roleList = roles == null || roles.isEmpty()
                    ? List.of() : Arrays.asList(roles.split(","));
            return new Claims(uid, name, roleList);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private byte[] sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static long extractLong(String payload, String key) {
        int i = payload.indexOf(key);
        if (i < 0) {
            throw new IllegalArgumentException("missing " + key);
        }
        int start = i + key.length();
        int end = start;
        while (end < payload.length() && (Character.isDigit(payload.charAt(end)) || payload.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(payload.substring(start, end));
    }

    private static String extractString(String payload, String key) {
        int i = payload.indexOf(key);
        if (i < 0) {
            return null;
        }
        int start = i + key.length();
        int end = payload.indexOf('"', start);
        if (end < 0) {
            throw new IllegalArgumentException("unterminated string");
        }
        return payload.substring(start, end);
    }

    /** 已校验的声明。 */
    public record Claims(long userId, String username, List<String> roles) {
        public boolean hasRole(String role) {
            return roles.contains(role);
        }
    }
}
