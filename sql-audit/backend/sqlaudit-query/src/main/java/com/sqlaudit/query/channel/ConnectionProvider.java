package com.sqlaudit.query.channel;

/**
 * 数据库连接提供者（设计文档 §6.2：查询连接与执行连接池物理隔离，ro_data 只读账号）。
 *
 * <p>生产实现由 storage 模块提供按 instanceId 路由的只读连接池；
 * 测试用 H2 内存库直连。</p>
 */
@FunctionalInterface
public interface ConnectionProvider {

    /**
     * 获取指定实例的查询连接（只读账号）。
     *
     * @param instanceId 实例 ID
     * @return JDBC 连接，调用方负责关闭（连接池场景 close 即归还）
     */
    java.sql.Connection getConnection(long instanceId) throws java.sql.SQLException;
}
