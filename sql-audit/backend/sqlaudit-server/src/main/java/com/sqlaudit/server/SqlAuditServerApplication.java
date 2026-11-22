package com.sqlaudit.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SQL 审核平台 API 入口（设计文档 §2.2：server 只连平台库，不连生产库）。
 *
 * <p>当前为 W2 装配版：平台库用内存 fake DAO（真 MyBatis 实现待接入）、
 * 查询通道用 H2 演示连接（生产为只读副本连接池）、鉴权未启用（auth 模块空壳，
 * approverId 由请求参数传入，W2 后段接 JWT 后改从上下文取）。</p>
 */
@SpringBootApplication
public class SqlAuditServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlAuditServerApplication.class, args);
    }
}
