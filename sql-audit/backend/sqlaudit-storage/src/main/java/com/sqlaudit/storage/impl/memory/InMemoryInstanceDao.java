package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.common.enums.InstanceStatus;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.model.Instance;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link InstanceDao} 的内存 fake 实现（供配置 CRUD 单测与本地联调解耦真实数据库）。
 *
 * <p>语义对齐接口 javadoc：删除为软删（置 deleted_at）；{@code listAll} /
 * {@code findById} 只返回未删记录；查询返回快照拷贝。</p>
 */
public class InMemoryInstanceDao implements InstanceDao {

    private final Map<Long, Instance> store = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    @Override
    public synchronized List<Instance> listAll() {
        List<Instance> result = new ArrayList<>();
        for (Instance instance : store.values()) {
            if (instance.getDeletedAt() == null) {
                result.add(copy(instance));
            }
        }
        result.sort(Comparator.comparing(Instance::getId));
        return result;
    }

    @Override
    public synchronized Instance findById(long id) {
        Instance instance = store.get(id);
        if (instance == null || instance.getDeletedAt() != null) {
            return null;
        }
        return copy(instance);
    }

    @Override
    public synchronized long insert(Instance instance) {
        long id = idGenerator.incrementAndGet();
        instance.setId(id);
        LocalDateTime now = LocalDateTime.now();
        if (instance.getStatus() == null) {
            instance.setStatus(InstanceStatus.ACTIVE);
        }
        if (instance.getCreatedAt() == null) {
            instance.setCreatedAt(now);
        }
        if (instance.getUpdatedAt() == null) {
            instance.setUpdatedAt(now);
        }
        store.put(id, copy(instance));
        return id;
    }

    @Override
    public synchronized int update(Instance instance) {
        Instance existing = store.get(instance.getId());
        if (existing == null || existing.getDeletedAt() != null) {
            return 0;
        }
        existing.setName(instance.getName());
        existing.setDialect(instance.getDialect());
        existing.setEnvironment(instance.getEnvironment());
        existing.setOwnerId(instance.getOwnerId());
        existing.setHost(instance.getHost());
        existing.setPort(instance.getPort());
        existing.setDefaultSchema(instance.getDefaultSchema());
        existing.setReplicaDsn(instance.getReplicaDsn());
        existing.setCredentialId(instance.getCredentialId());
        existing.setStatus(instance.getStatus());
        existing.setUpdatedAt(LocalDateTime.now());
        return 1;
    }

    @Override
    public synchronized int delete(long id) {
        Instance existing = store.get(id);
        if (existing == null || existing.getDeletedAt() != null) {
            return 0;
        }
        existing.setDeletedAt(LocalDateTime.now());
        return 1;
    }

    private static Instance copy(Instance src) {
        Instance dest = new Instance();
        dest.setId(src.getId());
        dest.setName(src.getName());
        dest.setDialect(src.getDialect());
        dest.setEnvironment(src.getEnvironment());
        dest.setOwnerId(src.getOwnerId());
        dest.setHost(src.getHost());
        dest.setPort(src.getPort());
        dest.setDefaultSchema(src.getDefaultSchema());
        dest.setReplicaDsn(src.getReplicaDsn());
        dest.setCredentialId(src.getCredentialId());
        dest.setStatus(src.getStatus());
        dest.setCreatedBy(src.getCreatedBy());
        dest.setCreatedAt(src.getCreatedAt());
        dest.setUpdatedAt(src.getUpdatedAt());
        dest.setDeletedAt(src.getDeletedAt());
        return dest;
    }
}
