package com.sqlaudit.storage.impl.jdbc.postgresql;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.common.enums.ApproverType;
import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.impl.jdbc.JdbcTx;
import com.sqlaudit.storage.model.Approval;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * approval 的 PostgreSQL 真实现（t_approval 表，SQL 逐句对照 MySQL 版
 * {@code MySqlApprovalDao}）。
 *
 * <ul>
 *   <li>{@code insert} 撞唯一键 {@code uk_approval_assignee(ticket_id, node_order,
 *       approver_type, approver_ref)} 返回 0（并发重复生成审批链不是错误，
 *       调用方据此判「已被别人生成」）；</li>
 *   <li>{@code updateResult} 条件更新（{@code WHERE result=fromResult}），只写
 *       {@code decided_by} / {@code result} / {@code comment} / {@code decided_at}，
 *       指派对象（approver_type / approver_ref）不被覆盖；</li>
 *   <li>{@code transfer} 在事务内两步完成（原记录置 TRANSFERRED 留痕 + 新 USER 记录指向
 *       代理人）——只做一半会让该组凭空少一个审批人或多一个；</li>
 *   <li>{@code listPending} 按 {@code (created_at, id)} 排序，走 (result, created_at) 索引。</li>
 * </ul>
 */
public class PostgresApprovalDao implements ApprovalDao {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PostgresApprovalDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.tx = JdbcTx.of(jdbc);
    }

    private static final RowMapper<Approval> MAPPER = (rs, i) -> {
        Approval a = new Approval();
        a.setId(rs.getLong("id"));
        a.setTicketId(rs.getLong("ticket_id"));
        a.setFlowNodeId(rs.getLong("flow_node_id"));
        a.setNodeOrder(rs.getInt("node_order"));
        a.setMode(ApprovalMode.fromCode(rs.getString("mode")));
        a.setApproverType(ApproverType.fromCode(rs.getString("approver_type")));
        a.setApproverRef(rs.getString("approver_ref"));
        a.setResult(ApprovalResult.fromCode(rs.getString("result")));
        a.setDecidedBy(rs.getObject("decided_by", Long.class));
        a.setComment(rs.getString("comment"));
        Timestamp decided = rs.getTimestamp("decided_at");
        a.setDecidedAt(decided == null ? null : decided.toLocalDateTime());
        Timestamp created = rs.getTimestamp("created_at");
        a.setCreatedAt(created == null ? null : created.toLocalDateTime());
        return a;
    };

    @Override
    public long insert(Approval approval) {
        try {
            long id = jdbc.query(conn -> {
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO t_approval (ticket_id, flow_node_id, node_order, mode, approver_type, "
                                + "approver_ref, result, decided_by, comment, decided_at, created_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id");
                ps.setLong(1, approval.getTicketId());
                ps.setLong(2, approval.getFlowNodeId());
                ps.setInt(3, approval.getNodeOrder());
                ps.setString(4, CodedEnum.code(approval.getMode()));
                ps.setString(5, CodedEnum.code(approval.getApproverType()));
                ps.setString(6, approval.getApproverRef());
                ps.setString(7, CodedEnum.code(approval.getResult()));
                if (approval.getDecidedBy() == null) {
                    ps.setNull(8, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(8, approval.getDecidedBy());
                }
                ps.setString(9, approval.getComment());
                ps.setTimestamp(10, approval.getDecidedAt() == null
                        ? null : Timestamp.valueOf(approval.getDecidedAt()));
                ps.setTimestamp(11, Timestamp.valueOf(approval.getCreatedAt() != null
                        ? approval.getCreatedAt() : LocalDateTime.now()));
                return ps;
            }, rs -> {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new IllegalStateException("INSERT ... RETURNING id 未返回任何行");
            });
            approval.setId(id);
            return id;
        } catch (DuplicateKeyException e) {
            return 0L; // uk_approval_assignee 冲突：同一工单同一组已有同样的指派对象
        }
    }

    @Override
    public List<Approval> listByTicket(long ticketId) {
        return jdbc.query(
                "SELECT * FROM t_approval WHERE ticket_id=? ORDER BY node_order, id",
                MAPPER, ticketId);
    }

    @Override
    public int updateResult(long id, long decidedBy, ApprovalResult fromResult, ApprovalResult toResult,
                            String comment, LocalDateTime decidedAt) {
        return jdbc.update(
                "UPDATE t_approval SET result=?, decided_by=?, comment=?, decided_at=? "
                        + "WHERE id=? AND result=?",
                CodedEnum.code(toResult), decidedBy, comment, Timestamp.valueOf(decidedAt),
                id, CodedEnum.code(fromResult));
    }

    @Override
    public List<Approval> listPending() {
        return jdbc.query(
                "SELECT * FROM t_approval WHERE result=? ORDER BY created_at, id",
                MAPPER, ApprovalResult.PENDING.getCode());
    }

    @Override
    public long transfer(long id, long fromUserId, long toUserId, LocalDateTime transferredAt) {
        Long newId = tx.execute(status -> {
            List<Approval> found = jdbc.query("SELECT * FROM t_approval WHERE id=?", MAPPER, id);
            if (found.isEmpty() || found.get(0).getResult() != ApprovalResult.PENDING) {
                return 0L;
            }
            Approval original = found.get(0);
            // 条件更新兜住并发：同一条记录被两个人同时转交时只有一个成功
            int affected = jdbc.update(
                    "UPDATE t_approval SET result=?, decided_by=?, decided_at=? WHERE id=? AND result=?",
                    ApprovalResult.TRANSFERRED.getCode(), fromUserId, Timestamp.valueOf(transferredAt),
                    id, ApprovalResult.PENDING.getCode());
            if (affected == 0) {
                return 0L;
            }
            Approval delegate = new Approval();
            delegate.setTicketId(original.getTicketId());
            delegate.setFlowNodeId(original.getFlowNodeId());
            delegate.setNodeOrder(original.getNodeOrder());
            delegate.setMode(original.getMode());
            delegate.setApproverType(ApproverType.USER);
            delegate.setApproverRef(String.valueOf(toUserId));
            delegate.setResult(ApprovalResult.PENDING);
            delegate.setCreatedAt(transferredAt);
            long inserted = insert(delegate);
            if (inserted == 0L) {
                // 代理人已在本组（撞唯一键）：整笔回滚，原记录保持 PENDING
                status.setRollbackOnly();
                return 0L;
            }
            return inserted;
        });
        return newId == null ? 0L : newId;
    }
}
