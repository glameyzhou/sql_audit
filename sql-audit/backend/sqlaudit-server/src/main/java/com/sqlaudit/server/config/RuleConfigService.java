package com.sqlaudit.server.config;

import com.sqlaudit.common.enums.RuleCode;
import com.sqlaudit.common.enums.RuleSeverity;
import com.sqlaudit.rule.RuleConfig;
import com.sqlaudit.storage.dao.RuleDao;
import com.sqlaudit.storage.model.Rule;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 规则运行时配置源：从 t_rule 读取「启用 / 级别」覆盖，喂给
 * {@code RuleEngine}（见 sql-audit-rbac-权限可配置化设计 落地后续）。
 *
 * <p>规则 CRUD 后由 ConfigController 调 {@link #reload()} 写即失效；启动时构造加载一次。
 * 自定义规则码（不在 {@link RuleCode} 内置清单）不在引擎内，忽略。nonOverridable 规则
 * 不可降级由引擎守卫。</p>
 */
@Service
public class RuleConfigService implements RuleConfig {

    private final RuleDao ruleDao;
    private volatile Map<RuleCode, Setting> snapshot = Map.of();

    public RuleConfigService(RuleDao ruleDao) {
        this.ruleDao = ruleDao;
        reload();
    }

    /** 从 t_rule 重载配置。 */
    public synchronized void reload() {
        Map<RuleCode, Setting> m = new HashMap<>();
        for (Rule r : ruleDao.listAll()) {
            RuleCode code = safeCode(r.getCode());
            if (code == null) {
                continue;
            }
            boolean enabled = r.getEnabled() == null || r.getEnabled();
            m.put(code, new Setting(enabled, r.getSeverity()));
        }
        snapshot = Map.copyOf(m);
    }

    @Override
    public boolean isEnabled(RuleCode code) {
        Setting s = snapshot.get(code);
        return s == null || s.enabled();
    }

    @Override
    public RuleSeverity severityOverride(RuleCode code) {
        Setting s = snapshot.get(code);
        return s == null ? null : s.severity();
    }

    private static RuleCode safeCode(String code) {
        try {
            return RuleCode.fromCode(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record Setting(boolean enabled, RuleSeverity severity) {
    }
}
