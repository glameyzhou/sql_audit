package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.storage.dao.FlowRuleDao;
import com.sqlaudit.storage.model.FlowRule;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link FlowRuleDao} 的内存 fake 实现（供配置 CRUD 单测与本地联调解耦真实数据库）。
 *
 * <p>语义对齐接口 javadoc：删除为硬删；查询返回快照拷贝。</p>
 */
public class InMemoryFlowRuleDao implements FlowRuleDao {

    private final Map<Long, FlowRule> store = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    @Override
    public synchronized List<FlowRule> listAll() {
        List<FlowRule> result = new ArrayList<>();
        for (FlowRule flowRule : store.values()) {
            result.add(copy(flowRule));
        }
        result.sort(Comparator.comparing(FlowRule::getId));
        return result;
    }

    @Override
    public synchronized FlowRule findById(long id) {
        FlowRule flowRule = store.get(id);
        return flowRule == null ? null : copy(flowRule);
    }

    @Override
    public synchronized long insert(FlowRule flowRule) {
        long id = idGenerator.incrementAndGet();
        flowRule.setId(id);
        LocalDateTime now = LocalDateTime.now();
        if (flowRule.getNonOverridable() == null) {
            flowRule.setNonOverridable(false);
        }
        if (flowRule.getPriority() == null) {
            flowRule.setPriority(0);
        }
        if (flowRule.getEnabled() == null) {
            flowRule.setEnabled(true);
        }
        if (flowRule.getCreatedAt() == null) {
            flowRule.setCreatedAt(now);
        }
        if (flowRule.getUpdatedAt() == null) {
            flowRule.setUpdatedAt(now);
        }
        store.put(id, copy(flowRule));
        return id;
    }

    @Override
    public synchronized int update(FlowRule flowRule) {
        FlowRule existing = store.get(flowRule.getId());
        if (existing == null) {
            return 0;
        }
        existing.setName(flowRule.getName());
        existing.setEnvironment(flowRule.getEnvironment());
        existing.setInstanceId(flowRule.getInstanceId());
        existing.setRiskLevel(flowRule.getRiskLevel());
        existing.setNonOverridable(flowRule.getNonOverridable());
        existing.setPriority(flowRule.getPriority());
        existing.setEnabled(flowRule.getEnabled());
        existing.setUpdatedAt(LocalDateTime.now());
        return 1;
    }

    @Override
    public synchronized int delete(long id) {
        return store.remove(id) == null ? 0 : 1;
    }

    private static FlowRule copy(FlowRule src) {
        FlowRule dest = new FlowRule();
        dest.setId(src.getId());
        dest.setName(src.getName());
        dest.setEnvironment(src.getEnvironment());
        dest.setInstanceId(src.getInstanceId());
        dest.setRiskLevel(src.getRiskLevel());
        dest.setNonOverridable(src.getNonOverridable());
        dest.setPriority(src.getPriority());
        dest.setEnabled(src.getEnabled());
        dest.setCreatedBy(src.getCreatedBy());
        dest.setCreatedAt(src.getCreatedAt());
        dest.setUpdatedAt(src.getUpdatedAt());
        return dest;
    }
}
