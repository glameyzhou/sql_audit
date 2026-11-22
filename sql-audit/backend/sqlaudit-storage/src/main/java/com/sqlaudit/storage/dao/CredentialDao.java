package com.sqlaudit.storage.dao;

import com.sqlaudit.storage.model.Credential;

import java.util.List;

/**
 * 生产库凭据存储（对应 t_credential 表）。
 *
 * <p>唯一写入方：sqlaudit-auth；读方：sqlaudit-worker（RW_EXEC 建连接）、sqlaudit-query
 * （RO_META/RO_DATA）。{@code passwordEnc} 恒为 AES-GCM 密文，解密在消费方（common 的
 * {@code CredentialCrypto}）完成，DAO 层不触碰明文。</p>
 */
public interface CredentialDao {

    /** 按实例 + 账号类型查（uk_inst_type 唯一，未找到返回 {@code null}）。 */
    Credential findByInstanceAndType(long instanceId, String accountType);

    /** 插入凭据，回填自增 id。 */
    long insert(Credential credential);

    /** 某实例下全部凭据，按 id 升序。 */
    List<Credential> listByInstance(long instanceId);
}
