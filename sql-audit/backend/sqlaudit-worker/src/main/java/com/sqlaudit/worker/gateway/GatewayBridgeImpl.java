package com.sqlaudit.worker.gateway;

import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.StatementType;
import com.sqlaudit.common.model.ParsedStatement;
import com.sqlaudit.executor.gateway.ProductionGateway;
import com.sqlaudit.parser.SqlParserService;
import com.sqlaudit.rollback.preimage.PreimagePlanner;
import com.sqlaudit.rule.TableMetadataProvider;
import com.sqlaudit.storage.model.TicketUnit;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * 生产库执行桥（把 unit 交给 {@link ProductionGateway}，前镜像 SQL 由
 * {@link PreimagePlanner} 现场构造）。
 *
 * <p>编排口径（§3.3 步骤 4 + §5.3）：</p>
 * <ul>
 *   <li>unit.statement 明文 → parser 解析（解析失败即 EXECUTION_FAILED，
 *       正常到不了这里——提交期已过规则审核，但防御性重解析仍必要：
 *       平台库数据可能被误改）；</li>
 *   <li>UPDATE/DELETE → PreimagePlanner 构造前镜像 SELECT（Worker 执行时刻构造，
 *       审核期不生成，§5.3 铁律）；</li>
 *   <li>INSERT → 无前镜像，gateway 按方言取主键（MySQL LAST_INSERT_ID / PG RETURNING）。</li>
 * </ul>
 */
public class GatewayBridgeImpl implements com.sqlaudit.executor.engine.UnitExecutionEngine.ProductionGatewayBridge {

    private final ProductionGateway gateway;
    private final SqlParserService parser;
    private final TableMetadataProvider metadata;
    private final PreimagePlanner preimagePlanner = new PreimagePlanner();

    public GatewayBridgeImpl(ProductionGateway gateway, SqlParserService parser,
                             TableMetadataProvider metadata) {
        this.gateway = Objects.requireNonNull(gateway);
        this.parser = Objects.requireNonNull(parser);
        this.metadata = Objects.requireNonNull(metadata);
    }

    @Override
    public ProductionGateway.ExecutionResult execute(TicketUnit unit) {
        String sql = new String(unit.getStatement(), StandardCharsets.UTF_8);
        Dialect dialect = dialectOf(unit);
        List<ParsedStatement> parsed = parser.parse(sql, dialect);
        if (parsed.isEmpty() || parsed.get(0).getParseConfidence() == 0) {
            return ProductionGateway.ExecutionResult.executionError(
                    "unit 语句重解析失败（平台库数据异常？）: " + sql);
        }
        ParsedStatement ps = parsed.get(0);

        ProductionGateway.ExecutionRequest request = buildRequest(unit, ps, dialect);
        if (request == null) {
            return ProductionGateway.ExecutionResult.executionError("前镜像构造失败（无主键元数据等），拒绝执行");
        }
        return gateway.executeInTransaction(request);
    }

    private ProductionGateway.ExecutionRequest buildRequest(TicketUnit unit, ParsedStatement ps,
                                                            Dialect dialect) {
        // INSERT：无前镜像（主键由 gateway 取）
        if (ps.getStatementType() == StatementType.INSERT) {
            return new ProductionGateway.ExecutionRequest(
                    ps.getOriginalSql(), null, null, List.of(), unit.getInstanceId());
        }

        // UPDATE / DELETE：现场构造前镜像 SELECT（§5.3）
        try {
            PreimagePlanner.PreimageQuery preimage;
            if (ps.getSourceStatement() instanceof SQLUpdateStatement update) {
                preimage = preimagePlanner.planUpdate(update, metadata, dialect);
            } else if (ps.getSourceStatement() instanceof SQLDeleteStatement delete) {
                preimage = preimagePlanner.planDelete(delete, metadata, dialect);
            } else {
                return simpleRequest(ps, unit);
            }
            TableMetadataProvider.TableMetadata tm = metadata.getTable(
                    unit.getSchemaName(), unit.getObjectName());
            List<String> pk = tm != null ? tm.primaryKeyColumns() : List.of();
            return new ProductionGateway.ExecutionRequest(
                    ps.getOriginalSql(), preimage.sqlText(), preimage.columns(), pk,
                    unit.getInstanceId());
        } catch (IllegalArgumentException e) {
            // 前镜像构造失败（无主键元数据等）→ 不连生产库，安全失败
            return null;
        }
    }

    private ProductionGateway.ExecutionRequest simpleRequest(ParsedStatement ps, TicketUnit unit) {
        return new ProductionGateway.ExecutionRequest(
                ps.getOriginalSql(), null, null, List.of(), unit.getInstanceId());
    }

    private static Dialect dialectOf(TicketUnit unit) {
        String hint = unit.getDialectSpecific();
        if (hint != null) {
            try {
                return Dialect.valueOf(hint.toUpperCase());
            } catch (IllegalArgumentException ignore) {
                // 落到默认 MYSQL
            }
        }
        return Dialect.MYSQL;
    }
}
