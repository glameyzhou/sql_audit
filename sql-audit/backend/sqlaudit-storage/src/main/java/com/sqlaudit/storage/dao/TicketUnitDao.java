package com.sqlaudit.storage.dao;

import com.sqlaudit.common.enums.UnitStatus;
import com.sqlaudit.storage.model.TicketUnit;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行单元 DAO 接口（设计文档 §3.3 任务队列与幂等协议）。
 *
 * <p><strong>W0 只定义接口，不含实现</strong>；流 A（sqlaudit-executor）用内存 fake
 * 实现本接口跑通单测，W2 集成时换 MyBatis 真实现。</p>
 *
 * <p>方法语义与 §3.3 的 SQL 一一对应，实现必须逐条保证：</p>
 * <ul>
 *   <li>{@code claimUnit} 抢占的硬条件是 {@code intent_written=0}，覆盖 pending 与租约过期的 leased；</li>
 *   <li>{@code confirmSuccess} 用条件 UPDATE，<strong>不靠唯一约束</strong>；影响行数 0 说明已被确认；</li>
 *   <li>租约回收由 reaper 用 {@code reclaimExpiredLease} / {@code listExecutingWithStaleHeartbeat} 分流，
 *       只认心跳不认耗时。</li>
 * </ul>
 */
public interface TicketUnitDao {

    /**
     * 抢占 unit（§3.3 步骤 1）。影响行数 1 才算抢到。
     *
     * @param leaseUntil  租约到期 = now + 30s
     * @param heartbeatAt 心跳时间 = now，之后每 5s 续租
     */
    int claimUnit(long id, String leaseOwner, LocalDateTime leaseUntil, LocalDateTime heartbeatAt);

    /**
     * 预写 intent（§3.3 步骤 3）：同平台库事务内写 {@code status='executing'}、
     * {@code intent_written=1}。事务提交后才允许连生产库。
     */
    int writeIntent(long id, LocalDateTime now);

    /** 幂等确认成功（§3.3 步骤 5）：条件 UPDATE，影响行数 0 表示已被确认，返回成功即可。 */
    int confirmSuccess(long id, long affectedRows, LocalDateTime executedAt);

    /** 确认失败（执行期出错，status='failed'）。 */
    int confirmFailed(long id, String errorMessage, LocalDateTime executedAt);

    /** 转 needs_confirm（§3.3 reaper：SQL 可能已执行但平台库状态未确认，禁止自动重试）。 */
    int markNeedsConfirm(long id, String reason);

    /** 释放租约退回 pending（抢占成功但拿不到 exec_lock 时，§3.3 步骤 2）。 */
    int releaseLeaseToPending(long id);

    /** reaper 回收：pending/leased 且租约过期且 intent_written=0，清租约回 pending。 */
    int reclaimExpiredLease(LocalDateTime now);

    /** reaper 查询：executing 且心跳停滞超过 TTL 且未确认的 unit（转 needs_confirm）。 */
    List<TicketUnit> listExecutingWithStaleHeartbeat(LocalDateTime heartbeatThreshold);

    TicketUnit findById(long id);

    /** 跨工单重复提交检测：同一 exec_fingerprint 在时间窗内是否已存在。 */
    List<TicketUnit> listByFingerprintSince(String execFingerprint, LocalDateTime since);

    /** worker 轮询候选（§3.3）：pending 或租约已过期（intent_written=0）的 unit，稳定排序。 */
    List<TicketUnit> listClaimable(LocalDateTime now, int limit);

    /** 心跳续租（worker 每 5s）：影响行数 0 = 租约已被回收或 unit 已流转。 */
    int renewLease(long id, String leaseOwner, LocalDateTime leaseUntil, LocalDateTime heartbeatAt);

    /** 工单详情：某工单的全部执行单元，按 unit_order 稳定排序。 */
    List<TicketUnit> listByTicket(long ticketId);

    /**
     * 更新表结构指纹（设计文档 §3.5）：审批通过时由 server 侧采集
     * {@code SHA256(SHOW CREATE TABLE)} 写入 {@code ddl_fingerprint}，执行前由
     * worker 侧重算比对（{@code R_SCHEMA_DRIFT} 门禁）。
     *
     * <p>语义 = {@code UPDATE t_ticket_unit SET ddl_fingerprint=? WHERE id=?}，
     * 返回影响行数（0 = unit 不存在）。DDL unit 审批时点对象尚不存在、表不存在等
     * 场景指纹留空，执行期 drift 门禁自动跳过。</p>
     *
     * @param id          unit id
     * @param fingerprint 结构指纹（SHA-256 hex；可为 null 表示清空/跳过）
     */
    int updateDdlFingerprint(long id, String fingerprint);

    /** 执行监控：按执行单元状态列表（executing/success/failed/needs_confirm 等），按 id 排序。 */
    List<TicketUnit> listByStatus(UnitStatus status);

    /**
     * 插入执行单元（工单创建时由 workflow 写入，status='pending' 起点）。
     *
     * @return 分配的自增 id（回填到 unit.id）
     */
    long insert(TicketUnit unit);
}
