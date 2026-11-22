package com.sqlaudit.storage.dao;

import com.sqlaudit.storage.model.RollbackSql;
import com.sqlaudit.storage.model.RollbackStatement;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回滚 DAO 接口（设计文档 §5.9 回滚 SQL 生命周期）。
 *
 * <p>存储模型为「主表 rollback_sql + 语句子表 rollback_statement」：
 * 每行一条反向语句，回放时分批流式读取 + 逐条幂等。</p>
 *
 * <p>W0 只定义接口，不含实现。</p>
 */
public interface RollbackDao {

    /** 插入回滚主记录（unit_id 唯一）。 */
    int insertRollbackSql(RollbackSql rollbackSql);

    /** 插入一条反向语句（rollback_id + stmt_order 唯一）。 */
    int insertRollbackStatement(RollbackStatement statement);

    /**
     * 分批流式读取反向语句（回放时按序读取，避免一次性加载全部进内存）。
     *
     * @param offset 从第几条开始
     * @param limit  单批条数
     */
    List<RollbackStatement> listStatements(long rollbackId, int offset, int limit);

    /** 逐条幂等回放：{@code UPDATE ... SET applied_at=? WHERE id=? AND applied_at IS NULL}。 */
    int markStatementApplied(long statementId, LocalDateTime appliedAt);

    /** 更新回滚主记录状态（generated → verified → applied/expired/not_available）。 */
    int updateStatus(long id, String status, String unavailableReason);

    RollbackSql findByUnitId(long unitId);

    /** 回滚管理：全部回滚主记录，按 id 倒序（最新在前）。 */
    List<RollbackSql> listAll();
}
