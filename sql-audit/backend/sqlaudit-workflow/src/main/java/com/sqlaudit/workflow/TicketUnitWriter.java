package com.sqlaudit.workflow;

import com.sqlaudit.storage.model.TicketUnit;

/**
 * 工单创建时的 unit 落库出口（B-3 临时抽象）。
 *
 * <p>W0-3 冻结的 {@code TicketUnitDao} 只含执行态方法（claim/confirm 等，流 A 语义），
 * <strong>没有 insert</strong>；而 B-3 的 createTicket 需要「一条语句 = 一个 unit」地
 * 落库创建态 unit。为不改动已冻结接口，workflow 侧用本函数式接口抽象插入动作，
 * 测试装配 {@code inMemoryTicketUnitDao::insert}，W2 换 MyBatis 真实现后由
 * DAO 层直接提供 insert 并移除本接口。</p>
 */
@FunctionalInterface
public interface TicketUnitWriter {

    /** 插入 unit，返回自增 id。 */
    long insert(TicketUnit unit);
}
