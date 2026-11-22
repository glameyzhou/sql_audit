package com.sqlaudit.common.crypto;

import com.sqlaudit.common.exception.SqlauditException;

/**
 * 生产库凭据 AES-GCM 加解密异常（密文被篡改 / 密钥不匹配 / 密文格式非法）。
 *
 * <p>供 {@code JdbcProductionGateway} 捕获后映射为 {@code executionError("凭据解密失败")}，
 * 避免把底层 {@code AEADBadTagException} 等细节泄露到执行结果。</p>
 */
public class CredentialCryptoException extends SqlauditException {

    public CredentialCryptoException(String message) {
        super("CREDENTIAL_CRYPTO", message);
    }

    public CredentialCryptoException(String message, Throwable cause) {
        super("CREDENTIAL_CRYPTO", message, cause);
    }
}
