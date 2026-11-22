package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.storage.dao.RuleDao;
import com.sqlaudit.storage.model.Rule;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link RuleDao} 的内存 fake 实现（供配置 CRUD 单测与本地联调解耦真实数据库）。
 *
 * <p>语义对齐接口 javadoc：删除为硬删；查询返回快照拷贝。</p>
 */
public class InMemoryRuleDao implements RuleDao {

    private final Map<Long, Rule> store = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    @Override
    public synchronized List<Rule> listAll() {
        List<Rule> result = new ArrayList<>();
        for (Rule rule : store.values()) {
            result.add(copy(rule));
        }
        result.sort(Comparator.comparing(Rule::getId));
        return result;
    }

    @Override
    public synchronized long insert(Rule rule) {
        long id = idGenerator.incrementAndGet();
        rule.setId(id);
        if (rule.getEnabled() == null) {
            rule.setEnabled(true);
        }
        if (rule.getCreatedAt() == null) {
            rule.setCreatedAt(LocalDateTime.now());
        }
        store.put(id, copy(rule));
        return id;
    }

    @Override
    public synchronized int update(Rule rule) {
        Rule existing = store.get(rule.getId());
        if (existing == null) {
            return 0;
        }
        existing.setCode(rule.getCode());
        existing.setDialect(rule.getDialect());
        existing.setStatementType(rule.getStatementType());
        existing.setSeverity(rule.getSeverity());
        existing.setEnabled(rule.getEnabled());
        existing.setParams(rule.getParams());
        existing.setDescription(rule.getDescription());
        return 1;
    }

    @Override
    public synchronized int delete(long id) {
        return store.remove(id) == null ? 0 : 1;
    }

    private static Rule copy(Rule src) {
        Rule dest = new Rule();
        dest.setId(src.getId());
        dest.setCode(src.getCode());
        dest.setDialect(src.getDialect());
        dest.setStatementType(src.getStatementType());
        dest.setSeverity(src.getSeverity());
        dest.setEnabled(src.getEnabled());
        dest.setParams(src.getParams());
        dest.setDescription(src.getDescription());
        dest.setCreatedAt(src.getCreatedAt());
        return dest;
    }
}
