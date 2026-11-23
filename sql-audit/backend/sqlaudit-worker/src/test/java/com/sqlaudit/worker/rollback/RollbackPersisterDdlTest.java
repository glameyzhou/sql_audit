package com.sqlaudit.worker.rollback;

import com.sqlaudit.common.enums.RollbackCoverage;
import com.sqlaudit.common.enums.RollbackStatus;
import com.sqlaudit.executor.gateway.ProductionGateway;
import com.sqlaudit.parser.SqlParserService;
import com.sqlaudit.rollback.lifecycle.RollbackLifecycleService;
import com.sqlaudit.storage.impl.memory.InMemoryRollbackDao;
import com.sqlaudit.storage.model.RollbackSql;
import com.sqlaudit.storage.model.RollbackStatement;
import com.sqlaudit.storage.model.TicketUnit;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RollbackPersisterImpl 的 DDL 反向落库用例（§5.2 可逆 DDL 全覆盖）。
 *
 * <p>DDL 无前镜像，反向语句由 {@code DdlReverseMapper} 按 AST 形态生成；
 * 不认识的 DDL 形态转 needs_confirm 人工补录（绝不静默跳过）。</p>
 */
class RollbackPersisterDdlTest {

    private final SqlParserService parser = new SqlParserService();
    private final InMemoryRollbackDao rollbackDao = new InMemoryRollbackDao();
    private final RollbackPersisterImpl persister = new RollbackPersisterImpl(
            new RollbackLifecycleService(rollbackDao, parser), parser, stubMetadata());

    /** DDL 用例不需要元数据（走 DdlReverseMapper），给个恒可用 stub 即可。 */
    private static com.sqlaudit.rule.TableMetadataProvider stubMetadata() {
        return new com.sqlaudit.rule.TableMetadataProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public TableMetadata getTable(String schema, String name) {
                return new TableMetadata(true, java.util.List.of("id"),
                        java.util.Set.of(), java.util.List.of("id", "a", "b"),
                        java.util.Set.of(), true);
            }
        };
    }

    @Test
    void createTable_persistsDropTableReverse() {
        TicketUnit unit = new TicketUnit();
        unit.setId(7L);
        unit.setStatement("CREATE TABLE t_demo (id INT PRIMARY KEY, a INT)"
                .getBytes(StandardCharsets.UTF_8));
        unit.setDialectSpecific("MYSQL");

        ProductionGateway.ExecutionResult result = ProductionGateway.ExecutionResult.success(
                null, 0, null);

        assertTrue(persister.persist(unit, result), "DDL 反向落库应成功");

        List<RollbackSql> rolls = rollbackDao.listAll();
        assertEquals(1, rolls.size(), "应有一条回滚主记录");
        assertEquals(RollbackStatus.VERIFIED, rolls.get(0).getStatus(), "语法自检通过应为 verified");
        assertEquals(RollbackCoverage.FULL, rolls.get(0).getCoverage(), "可逆 DDL 回滚覆盖为 FULL");

        List<RollbackStatement> statements = rollbackDao.listStatements(rolls.get(0).getId(), 0, 10);
        assertEquals(1, statements.size(), "应有一条反向语句");
        String reverse = new String(statements.get(0).getReverseSql(), StandardCharsets.UTF_8);
        assertTrue(reverse.contains("DROP TABLE"), "反向语句应为 DROP TABLE，实际: " + reverse);
    }

    @Test
    void unknownDdlShape_persistReturnsFalse() {
        TicketUnit unit = new TicketUnit();
        unit.setId(8L);
        // ALTER TABLE 多列 ADD（mapper 首版不支持）→ 应拒绝落库转人工，而不是静默成功
        unit.setStatement("ALTER TABLE t_demo ADD COLUMN a INT, ADD COLUMN b INT"
                .getBytes(StandardCharsets.UTF_8));
        unit.setDialectSpecific("MYSQL");

        ProductionGateway.ExecutionResult result = ProductionGateway.ExecutionResult.success(
                null, 0, null);

        assertTrue(!persister.persist(unit, result),
                "不认识的 DDL 形态应转 needs_confirm（返回 false）");
    }
}
