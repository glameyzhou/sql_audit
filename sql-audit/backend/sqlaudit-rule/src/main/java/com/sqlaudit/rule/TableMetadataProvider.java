package com.sqlaudit.rule;

import java.util.List;
import java.util.Set;

/**
 * 表元数据提供者接口（规则引擎的外部依赖）。
 *
 * <p>生产实现由 worker/executor 通过 `ro_meta` 账号查 information_schema 提供
 * （含 README §4.4 的生成列过滤所需的 is_generated）；测试用内存 fake。</p>
 *
 * <p>提供方返回 null 表示「表不存在」——R_OBJECT_NOT_ALLOWED 会接住；
 * 提供 {@link #isAvailable()} = false 表示元数据暂不可达，依赖元数据的规则
 * 按「无法证明安全」保守处理。</p>
 */
public interface TableMetadataProvider {

    /** 元数据服务是否可用（不可用时依赖元数据的规则保守判定）。 */
    boolean isAvailable();

    /**
     * 查表元数据。
     *
     * @return 表存在时返回元数据；不存在返回 null
     */
    TableMetadata getTable(String schema, String name);

    /** 表元数据（information_schema 摘要）。 */
    record TableMetadata(
            boolean hasPrimaryKey,
            List<String> primaryKeyColumns,
            /** 生成列（VIRTUAL/STORED / GENERATED ALWAYS AS），反向 INSERT 必须排除（README §4.4） */
            Set<String> generatedColumns,
            List<String> columns,
            /** 掩码列（命中 masking_rule），未授权访问时触发 R_SENSITIVE_COLUMN */
            Set<String> sensitiveColumns,
            /** 实例对象白名单是否包含此表（R_OBJECT_NOT_ALLOWED） */
            boolean whitelisted) {
    }
}
