package com.sqlaudit.server.service;

import com.sqlaudit.common.crypto.CredentialCrypto;
import com.sqlaudit.common.enums.AccountType;
import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.storage.dao.CredentialDao;
import com.sqlaudit.storage.model.Credential;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 实例凭据随实例保存（系统配置 → 实例管理的新增 / 编辑）。
 *
 * <p>产品口径：<strong>所有环境（dev / test / prod）的实例都带凭据</strong>，不再按环境做
 * 特例，也不再要求手工 SQL 种子。实例表单只暴露一组「账号 / 密码」，保存时同时写入
 * {@link AccountType#RO_META}（库表浏览 / 审批取结构指纹）与 {@link AccountType#RW_EXEC}
 * （worker 执行 / 回滚）两条凭据，两条账号密码相同；后续如需按最小权限拆分，可在
 * t_credential 层单独轮换某一类账号而不影响另一类。</p>
 *
 * <p>密码一律经 {@link CredentialCrypto#encrypt} 加密后落库（{@code password_enc}），
 * 不回显、不进日志、不进审计 detail；对外只暴露账号名。</p>
 */
@Service
public class InstanceCredentialService {

    private final CredentialDao credentialDao;

    public InstanceCredentialService(CredentialDao credentialDao) {
        this.credentialDao = credentialDao;
    }

    /**
     * 写入实例的一组账号密码（RO_META + RW_EXEC，存在则更新、不存在则新增）。
     *
     * @throws SqlauditException 账号或密码为空（HTTP 400）
     */
    public void savePair(long instanceId, String username, String password) {
        String user = username == null ? "" : username.trim();
        if (user.isEmpty()) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "凭据账号不能为空");
        }
        if (password == null || password.isEmpty()) {
            throw new SqlauditException(ErrorCode.INVALID_REQUEST, "凭据密码不能为空");
        }
        upsert(instanceId, AccountType.RO_META, user, password);
        upsert(instanceId, AccountType.RW_EXEC, user, password);
    }

    /**
     * 凭据概要（编辑实例回填账号名用）：只返回 accountType / username / 轮换时间，
     * <strong>绝不返回密码或密文</strong>。
     */
    public List<Map<String, Object>> describe(long instanceId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Credential c : credentialDao.listByInstance(instanceId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountType", c.getAccountType() == null
                    ? null : c.getAccountType().getCode().toUpperCase(Locale.ROOT));
            m.put("username", c.getUsername());
            m.put("lastRotatedAt", c.getLastRotatedAt());
            out.add(m);
        }
        return out;
    }

    private void upsert(long instanceId, AccountType type, String username, String password) {
        String passwordEnc = CredentialCrypto.encrypt(password);
        LocalDateTime now = LocalDateTime.now();
        Credential existing = credentialDao.findByInstanceAndType(instanceId, type);
        if (existing == null) {
            Credential credential = new Credential();
            credential.setInstanceId(instanceId);
            credential.setAccountType(type);
            credential.setUsername(username);
            credential.setPasswordEnc(passwordEnc);
            credential.setKeyVersion(1);
            credential.setLastRotatedAt(now);
            credential.setCreatedAt(now);
            credential.setUpdatedAt(now);
            credentialDao.insert(credential);
            return;
        }
        existing.setUsername(username);
        existing.setPasswordEnc(passwordEnc);
        existing.setLastRotatedAt(now);
        existing.setUpdatedAt(now);
        credentialDao.update(existing);
    }
}
