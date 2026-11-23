package com.sqlaudit.storage.impl.jdbc.postgresql;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.common.enums.ApproverType;
import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.model.Approval;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * approval 的 PostgreSQL 真实现（DDL §8 审批记录表；内存版语义基准 {@code InMemoryApprovalDao}，
 * SQL 逐句对照 MySQL 版 {@code MySqlApprovalDao}）。
 *
 * <p><strong>与内存版语义逐条对照：</strong></p>
 * <ul>
 *   <li>{@code updateResult} 条件更新（{@code WHERE result=fromResult}，通常 PENDING），
 *       影响行数 0 = 已被决策 / 记录不存在——不会被覆盖；同时把实际决策人 approverId
 *       写入记录（ROLE 占位记录被决策人「认领」）；</li>
 *   <li>{@code listByTicket} 按 {@code (node_order, id)} 稳定排序，同 node_order 多行即会签/或签组；</li>
 *   <li>{@code listPendingCreatedBefore}：PENDING 且 {@code created_at < threshold}（内存版 isBefore 同款开区间），
 *       按 {@code (created_at, id)} 排序；</li>
 *   <li>{@code listPending}（非接口方法，照内存版）：全部 PENDING，server「待我审批」列表用。</li>
 * </ul>
 *
 * <p><strong>与 MySQL 版的方言差异：</strong>自增 id 回填由 {@code GeneratedKeyHolder}
 * 改为 {@code INSERT ... RETURNING id}。事务边界由调用方保证，本类不开事务。</p>
 */
public class PostgresApprovalDao implements ApprovalDao {

    private final JdbcTemplate jdbc;

    public PostgresApprovalDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Approval> MAPPER = (rs, i) -> {
        Approval a = new Approval();
        a.setId(rs.getLong("id"));
        a.setTicketId(rs.getLong("ticket_id"));
        a.setNodeId(rs.getLong("node_id"));
        a.setNodeOrder(rs.getInt("node_order"));
        a.setApproverId(rs.getLong("approver_id"));
        a.setApproverType(ApproverType.fromCode(rs.getString("approver_type")));
        a.setApproverRef(rs.getString("approver_ref"));
        a.setMode(ApprovalMode.fromCode(rs.getString("mode")));
        a.setResult(ApprovalResult.fromCode(rs.getString("result")));
        a.setComment(rs.getString("comment"));
        Timestamp decided = rs.getTimestamp("decided_at");
        a.setDecidedAt(decided == null ? null : decided.toLocalDateTime());
        Timestamp created = rs.getTimestamp("created_at");
        a.setCreatedAt(created == null ? null : created.toLocalDateTime());
        return a;
    };

    @Override
    public long insert(Approval approval) {
        long id = jdbc.queryForObject(
                "INSERT INTO t_approval (ticket_id, node_id, node_order, approver_id, approver_type, approver_ref, "
                        + "mode, result, comment, decided_at, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                approval.getTicketId(),
                approval.getNodeId(),
                approval.getNodeOrder(),
                approval.getApproverId(),
                CodedEnum.code(approval.getApproverType()),
                approval.getApproverRef(),
                CodedEnum.code(approval.getMode()),
                CodedEnum.code(approval.getResult()),
                approval.getComment(),
                approval.getDecidedAt() == null ? null : Timestamp.valueOf(approval.getDecidedAt()),
                Timestamp.valueOf(approval.getCreatedAt() != null
                        ? approval.getCreatedAt() : LocalDateTime.now()));
        approval.setId(id);
        return id;
    }

    @Override
    public List<Approval> listByTicket(long ticketId) {
        // (node_order, id) 稳定排序：节点串行推进，同 node_order 多行 = 会签/或签组
        return jdbc.query(
                "SELECT * FROM t_approval WHERE ticket_id=? ORDER BY node_order, id",
                MAPPER, ticketId);
    }

    @Override
    public int updateResult(long id, long approverId, ApprovalResult fromResult, ApprovalResult toResult,
                            String comment, LocalDateTime decidedAt) {
        // 条件 UPDATE：已被决策（fromResult 不匹配）→ 0，不覆盖既有决策与 comment
        return jdbc.update(
                "UPDATE t_approval SET approver_id=?, result=?, comment=?, decided_at=? "
                        + "WHERE id=? AND result=?",
                approverId, CodedEnum.code(toResult), comment, Timestamp.valueOf(decidedAt), id, CodedEnum.code(fromResult));
    }

    @Override
    public List<Approval> listPendingCreatedBefore(LocalDateTime threshold) {
        return jdbc.query(
                "SELECT * FROM t_approval WHERE result=? AND created_at < ? "
                        + "ORDER BY created_at, id",
                MAPPER, ApprovalResult.PENDING.getCode(), Timestamp.valueOf(threshold));
    }

    /** 全部 PENDING 审批（server「待我审批」列表用；非接口方法，照内存版）。 */
    @Override
    public List<Approval> listPending() {
        return jdbc.query(
                "SELECT * FROM t_approval WHERE result=? ORDER BY created_at, id",
                MAPPER, ApprovalResult.PENDING.getCode());
    }

    @Override
    public int transfer(long id, long fromUserId, long toUserId) {
        return jdbc.update(
                "UPDATE t_approval SET approver_id=? WHERE id=? AND approver_id=? AND result=?",
                toUserId, id, fromUserId, ApprovalResult.PENDING.getCode());
    }
}
