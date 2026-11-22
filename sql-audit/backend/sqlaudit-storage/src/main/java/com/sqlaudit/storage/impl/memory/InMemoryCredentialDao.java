package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.storage.dao.CredentialDao;
import com.sqlaudit.storage.model.Credential;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link CredentialDao} 的内存 fake 实现（供 worker/query 单测与本地联调解耦真实数据库）。
 *
 * <p>语义对齐接口 javadoc：{@code insert} 回填自增 id；{@code findByInstanceAndType} 未命中
 * 返回 {@code null}；查询返回快照拷贝。</p>
 */
public class InMemoryCredentialDao implements CredentialDao {

    private final Map<Long, Credential> store = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    @Override
    public synchronized Credential findByInstanceAndType(long instanceId, String accountType) {
        for (Credential c : store.values()) {
            if (c.getInstanceId() == instanceId && accountType.equals(c.getAccountType())) {
                return copy(c);
            }
        }
        return null;
    }

    @Override
    public synchronized long insert(Credential credential) {
        long id = idGenerator.incrementAndGet();
        credential.setId(id);
        LocalDateTime now = LocalDateTime.now();
        if (credential.getKeyVersion() == null) {
            credential.setKeyVersion(1);
        }
        if (credential.getCreatedAt() == null) {
            credential.setCreatedAt(now);
        }
        if (credential.getUpdatedAt() == null) {
            credential.setUpdatedAt(now);
        }
        store.put(id, copy(credential));
        return id;
    }

    @Override
    public synchronized List<Credential> listByInstance(long instanceId) {
        List<Credential> result = new ArrayList<>();
        for (Credential c : store.values()) {
            if (c.getInstanceId() == instanceId) {
                result.add(copy(c));
            }
        }
        result.sort(Comparator.comparing(Credential::getId));
        return result;
    }

    private static Credential copy(Credential src) {
        Credential dest = new Credential();
        dest.setId(src.getId());
        dest.setInstanceId(src.getInstanceId());
        dest.setAccountType(src.getAccountType());
        dest.setUsername(src.getUsername());
        dest.setPasswordEnc(src.getPasswordEnc());
        dest.setKeyVersion(src.getKeyVersion());
        dest.setLastRotatedAt(src.getLastRotatedAt());
        dest.setExpiresAt(src.getExpiresAt());
        dest.setCreatedAt(src.getCreatedAt());
        dest.setUpdatedAt(src.getUpdatedAt());
        return dest;
    }
}
