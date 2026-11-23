package com.sqlaudit.storage.dao;

import com.sqlaudit.storage.model.TableRemark;

import java.util.List;

/**
 * 表备注词典存储（对应 t_table_remark 表）。
 *
 * <p>唯一写入方：sqlaudit-server（{@code PUT /api/table-remarks}，{@code instance.manage}
 * 权限点）；读取方：查询台表列表的备注合并展示（{@code GET /api/table-remarks}，
 * {@code query.run} 权限点）。</p>
 *
 * <p>写语义：{@link #upsert} 同 (instance, schema, table) 已有行则更新备注与
 * {@code updated_at}（{@code created_by} 保持首次写入人）；「清空备注 = 删除行」由
 * 服务层转为 {@link #delete}，行内不存空串。</p>
 */
public interface TableRemarkDao {

    /** 某实例某库（PG 语义为 schema）下的全部备注，按 table_name 升序。 */
    List<TableRemark> findByInstanceAndSchema(long instanceId, String schemaName);

    /**
     * 写入或覆盖一条备注（按 uk_table_remark 判重）；{@code remark} 必须非空
     * （空值请走 {@link #delete}）。
     *
     * @return 受影响行数（MySQL 插入 1 / 覆盖 2；PG 恒 1）
     */
    int upsert(TableRemark remark);

    /** 删除一条备注（清空备注的落库动作）；返回删除行数（0 = 本来就没有）。 */
    int delete(long instanceId, String schemaName, String tableName);
}
