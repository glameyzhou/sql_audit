package com.sqlaudit.storage.impl.jdbc.h2;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApprovalResult;
import com.sqlaudit.common.enums.ApproverType;
import com.sqlaudit.common.enums.CodedEnum;
import com.sqlaudit.storage.dao.ApprovalDao;
import com.sqlaudit.storage.model.Approval;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * approval 的 H2 真实现（DDL §8 审批记录表；内存版语义基准 {@code InMemoryApprovalDao}，
 * SQL 逐句对照 PostgreSQL 版 {@code PostgresApprovalDao}）。
 *
 * <p><strong>与内存版语义逐条对照：</strong></p>
 * <ul>
 *   <li>{@code updateResult} 条件更新（{@code WHERE result=fromResult}，通常 PENDING），
 *       影响行数 0 = 已被决策 / 记录不存在——不会被覆盖；同时把实际决策人 approverId
 *       写入记录（ROLE 占位记录被决策人「认领」）；</li>
 *   <li>{@code listByTicket} 按 {@code (node_order, id)} 稳定排序，同 node_order 多行即会签/或签组；</li>
 *   <li>{@code listPendingCreatedBefore}：PENDING 且 {@code created_at < threshold}（内存版 isBefore 同款开区间），
 *       按 {@code (created_at, id)} 排序；</li>
 *   <li>{@code listPending}（接口方法，照内存版）：全部 PENDING，server「待我审批」列表用。</li>
 * </ul>
 *
 * <p><strong>与 PostgreSQL 版的方言差异：</strong>自增 id 回填由 {@code INSERT ... RETURNING}
 * 改为 {@code GeneratedKeyHolder}（H2 不支持 RETURNING）。事务边界由调用方保证，本类不开事务。</p>
 */
public class H2ApprovalDao implements ApprovalDao {

    private final JdbcTemplate jdbc;

    public H2ApprovalDao(JdbcTemplate jdbc) {
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
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO t_approval (ticket_id, node_id, node_order, approver_id, approver_type, approver_ref, "
                            + "mode, result, comment, decided_at, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setLong(1, approval.getTicketId());
            ps.setLong(2, approval.getNodeId());
            ps.setInt(3, approval.getNodeOrder());
            ps.setLong(4, approval.getApproverId());
            ps.setString(5, CodedEnum.code(approval.getApproverType()));
            ps.setString(6, approval.getApproverRef());
            ps.setString(7, CodedEnum.code(approval.getMode()));
            ps.setString(8, CodedEnum.code(approval.getResult()));
            ps.setString(9, approval.getComment());
            ps.setTimestamp(10, approval.getDecidedAt() == null ? null : Timestamp.valueOf(approval.getDecidedAt()));
            ps.setTimestamp(11, Timestamp.valueOf(approval.getCreatedAt() != null
                    ? approval.getCreatedAt() : LocalDateTime.now()));
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
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

    /** 全部 PENDING 审批（server「待我审批」列表用；照内存版）。 */
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
