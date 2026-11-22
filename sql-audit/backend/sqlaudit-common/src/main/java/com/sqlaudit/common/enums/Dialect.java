package com.sqlaudit.common.enums;

/**
 * SQL 方言。
 * <p>对应设计文档 §2.2 / §4.1，三类生产库账号物理隔离。</p>
 */
public enum Dialect {
    /** MySQL 8.0 */
    MYSQL,
    /** PostgreSQL 14+ */
    PG,
    /** Apache IoTDB 1.x */
    IOTDB
}
