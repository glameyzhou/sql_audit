package com.sqlaudit.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SQL 审核平台 API 入口（README §3.1：server 只连平台库，不连生产库）。
 *
 * <p>平台库只剩 MySQL / PostgreSQL 两个方言 profile（{@code mysql} / {@code postgres}），
 * 无默认内存方言——未激活方言 profile 时启动失败（DataSource 缺失）。
 * worker 已并入本进程：{@code WorkerIntegrationConfig} 恒装配 worker bean，执行循环由
 * {@code sqlaudit.worker.enabled}（默认 true）控制。鉴权为完全数据化的 RBAC
 * （拦截器按「HTTP 方法 + 处理器模式」精确查表，见 {@code RoleAuthInterceptor}）。</p>
 */
@SpringBootApplication
public class SqlAuditServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlAuditServerApplication.class, args);
    }
}
