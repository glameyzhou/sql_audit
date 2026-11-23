package com.sqlaudit.storage;

import com.sqlaudit.common.enums.RuleCode;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 种子数据契约测试（V2__seed.sql）。
 *
 * <p>两层断言：</p>
 * <ol>
 *   <li><strong>纯文件断言（不依赖数据库，始终运行）</strong>：mysql 与 postgresql 两份
 *       {@code V2__seed.sql} 逐字节相同——种子只有这一份来源，方言目录只是挂载点；</li>
 *   <li><strong>真库断言（MySQL 不可用时跳过）</strong>：在独立测试库 {@code sql_audit_it}
 *       上跑 Flyway V1+V2 后查表——规则 code 集合与 {@link RuleCode} 枚举一致、
 *       5 个内置角色、权限点与接口绑定非空、admin 经 SUPER_ADMIN 持有全部权限点、
 *       4 条全局审批流的节点形状与契约 §2.5 一致。</li>
 * </ol>
 */
class SeedDataEnumContractTest {

    private static HikariDataSource ds;
    private static JdbcTemplate jdbc;
    private static boolean mysqlUp;

    @BeforeAll
    static void initDatabase() throws Exception {
        mysqlUp = com.sqlaudit.storage.impl.jdbc.mysql.MySqlTestSupport.mysqlAvailable();
        if (!mysqlUp) {
            // 文件断言（两份种子逐字节相同）不依赖数据库、始终运行；
            // 真库断言在各测试方法内用 mysqlUp 自行跳过
            return;
        }
        com.sqlaudit.storage.impl.jdbc.mysql.MySqlTestSupport.ensureDatabase();
        ds = com.sqlaudit.storage.impl.jdbc.mysql.MySqlTestSupport.buildDataSource();
        jdbc = new JdbcTemplate(ds);
        com.sqlaudit.storage.impl.jdbc.mysql.MySqlTestSupport.migrate(ds);
    }

    @AfterAll
    static void closeDataSource() {
        if (ds != null) {
            ds.close();
        }
    }

    // ------------------------------------------------------------------
    // 文件断言（不依赖数据库，始终运行）
    // ------------------------------------------------------------------

    @Test
    void 两份V2种子逐字节相同() throws IOException {
        Path mysqlSeed = locateSeed("mysql");
        Path pgSeed = locateSeed("postgresql");
        byte[] mysqlBytes = Files.readAllBytes(mysqlSeed);
        byte[] pgBytes = Files.readAllBytes(pgSeed);
        assertTrue(mysqlBytes.length > 0, "种子文件应存在且非空: " + mysqlSeed);
        org.junit.jupiter.api.Assertions.assertArrayEquals(mysqlBytes, pgBytes,
                "mysql/postgresql 两份 V2__seed.sql 必须逐字节相同（种子单来源契约）");
    }

    private static Path locateSeed(String dialect) throws IOException {
        // 从模块目录向上找 src/main/resources（不依赖运行时 working dir 的具体层级）
        Path cwd = Path.of("").toAbsolutePath();
        Path candidate = cwd;
        for (int i = 0; i < 6 && candidate != null; i++) {
            Path target = candidate.resolve("sqlaudit-storage/src/main/resources/db/migration/"
                    + dialect + "/V2__seed.sql");
            if (Files.exists(target)) {
                return target;
            }
            candidate = candidate.getParent();
        }
        throw new IOException("未找到 " + dialect + " 的 V2__seed.sql（cwd=" + cwd + "）");
    }

    // ------------------------------------------------------------------
    // 真库断言（MySQL sql_audit_it 上 Flyway V1+V2 后查表）
    // ------------------------------------------------------------------

    @Test
    void 规则种子_26条_与RuleCode枚举完全一致() {
        if (!mysqlUp) {
            return;
        }
        List<String> codes = jdbc.queryForList("SELECT code FROM t_rule ORDER BY code", String.class);
        Set<String> expected = new TreeSet<>();
        for (RuleCode code : RuleCode.values()) {
            expected.add(code.name());
        }
        assertEquals(expected, new TreeSet<>(codes),
                "t_rule 的 code 集合必须与 RuleCode 枚举一一对应");
    }

    @Test
    void 角色_恰好5个内置角色_全部builtin与ALL数据范围() {
        if (!mysqlUp) {
            return;
        }
        List<Map<String, Object>> roles = jdbc.queryForList(
                "SELECT code, name, builtin, instance_scope FROM t_role ORDER BY id");
        assertEquals(List.of("SUPER_ADMIN", "ADMIN", "DBA", "LEAD", "DEV"),
                roles.stream().map(r -> (String) r.get("code")).toList(),
                "内置角色集合与契约 §2.1 一致");
        for (Map<String, Object> role : roles) {
            assertEquals(Boolean.TRUE, role.get("builtin"), "内置角色 builtin=TRUE");
            assertEquals("ALL", role.get("instance_scope"), "内置角色默认全部实例");
        }
    }

    @Test
    void 权限点_13个_接口绑定非空_且绑定都指向存在的权限点() {
        if (!mysqlUp) {
            return;
        }
        List<String> permissionCodes = jdbc.queryForList(
                "SELECT code FROM t_permission ORDER BY sort, id", String.class);
        assertEquals(new ArrayList<>(List.of(
                        "basic", "ticket.submit", "ticket.view_all", "approval.confirm", "execution.view",
                        "rollback.view", "rollback.execute", "query.run", "audit.view",
                        "rule.manage", "flow.manage", "instance.manage", "user.manage", "rbac.manage")),
                permissionCodes,
                "权限点目录与契约 §2.3 一致（basic 为 all_users，rbac.manage 为元权限）");

        Integer apiCount = jdbc.queryForObject("SELECT COUNT(*) FROM t_permission_api", Integer.class);
        assertTrue(apiCount != null && apiCount >= 40, "接口绑定非空（契约 §2.3 共 59 条）");

        // 反向：每条绑定都指向真实存在的权限点（逻辑外键，无物理约束，用查询兜底）
        Integer orphans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_permission_api a "
                        + "LEFT JOIN t_permission p ON p.id = a.permission_id WHERE p.id IS NULL",
                Integer.class);
        assertEquals(0, orphans, "t_permission_api 不得有孤儿绑定");
    }

    @Test
    void admin_经SUPER_ADMIN持有全部权限点_dev只有basic与query_run() {
        if (!mysqlUp) {
            return;
        }
        // admin（SUPER_ADMIN）→ 全部权限点
        List<String> adminRoles = jdbc.queryForList(
                "SELECT r.code FROM t_user_role ur "
                        + "JOIN t_user u ON u.id = ur.user_id "
                        + "JOIN t_role r ON r.id = ur.role_id "
                        + "WHERE u.username = 'admin' ORDER BY r.id", String.class);
        assertTrue(adminRoles.contains("SUPER_ADMIN"), "admin 绑定 SUPER_ADMIN");

        // 经角色持有的 = 全部「非 all_users」权限点（basic 是 all_users，契约 §2.4 不写授权行）
        List<String> grantedPermissions = jdbc.queryForList(
                "SELECT code FROM t_permission WHERE all_users = FALSE ORDER BY code", String.class);
        List<String> adminHeld = jdbc.queryForList(
                "SELECT DISTINCT p.code FROM t_permission p "
                        + "JOIN t_role_permission rp ON rp.permission_id = p.id "
                        + "JOIN t_user_role ur ON ur.role_id = rp.role_id "
                        + "JOIN t_user u ON u.id = ur.user_id "
                        + "WHERE u.username = 'admin' ORDER BY p.code", String.class);
        assertEquals(new HashSet<>(grantedPermissions), new HashSet<>(adminHeld),
                "admin（SUPER_ADMIN）经角色持有全部非 all_users 权限点（含元权限 rbac.manage）");
        assertTrue(adminHeld.contains("rbac.manage"), "元权限 rbac.manage 必须在授权集合内");
        Integer allUsersCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_permission WHERE all_users = TRUE", Integer.class);
        assertEquals(1, allUsersCount, "只有 basic 一个 all_users 权限点");

        // dev → basic 由 all_users 兜底（无授权行），经角色持有的只有 query.run
        List<String> devRoles = jdbc.queryForList(
                "SELECT r.code FROM t_user_role ur "
                        + "JOIN t_user u ON u.id = ur.user_id "
                        + "JOIN t_role r ON r.id = ur.role_id "
                        + "WHERE u.username = 'dev'", String.class);
        assertEquals(List.of("DEV"), devRoles, "dev 只绑定 DEV");
        List<String> devHeld = jdbc.queryForList(
                "SELECT DISTINCT p.code FROM t_permission p "
                        + "JOIN t_role_permission rp ON rp.permission_id = p.id "
                        + "JOIN t_user_role ur ON ur.role_id = rp.role_id "
                        + "JOIN t_user u ON u.id = ur.user_id "
                        + "WHERE u.username = 'dev' ORDER BY p.code", String.class);
        assertEquals(new HashSet<>(List.of("query.run", "ticket.submit")), new HashSet<>(devHeld),
                "dev 经角色持有 query.run + ticket.submit（契约 §2.4；basic 为 all_users 不写授权行）");
    }

    @Test
    void 账号种子_admin与dev存在且本地来源() {
        if (!mysqlUp) {
            return;
        }
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT username, source, status, password_hash FROM t_user ORDER BY id");
        assertEquals(List.of("admin", "dev"),
                users.stream().map(u -> (String) u.get("username")).toList(),
                "种子账号 admin / dev");
        for (Map<String, Object> user : users) {
            assertEquals("LOCAL", user.get("source"));
            assertEquals("ACTIVE", user.get("status"));
            assertTrue(((String) user.get("password_hash")).startsWith("$2"),
                    "password_hash 必须是 bcrypt（测试断言两账号可登录的前提）");
        }
    }

    @Test
    void 全局审批流_4条_节点形状与契约一致() {
        if (!mysqlUp) {
            return;
        }
        List<Map<String, Object>> flows = jdbc.queryForList(
                "SELECT id, name, instance_id, risk_level, enabled FROM t_flow_rule ORDER BY id");
        assertEquals(4, flows.size(), "4 条全局流程");
        Map<Integer, List<String>> nodesByOrder = new TreeMap<>();
        for (Map<String, Object> flow : flows) {
            assertEquals(null, flow.get("instance_id"), "种子流程全部为全局（instance_id 为 NULL）");
            assertEquals(Boolean.TRUE, flow.get("enabled"));
            long flowId = ((Number) flow.get("id")).longValue();
            String risk = (String) flow.get("risk_level");
            List<String> refs = jdbc.queryForList(
                    "SELECT approver_ref FROM t_flow_node WHERE flow_rule_id=? ORDER BY node_order, id",
                    String.class, flowId);
            switch (risk) {
                case "LOW" -> assertTrue(refs.isEmpty(), "LOW 免审（无节点）");
                case "MEDIUM" -> assertEquals(List.of("LEAD"), refs, "MEDIUM = 主管");
                case "HIGH" -> assertEquals(List.of("LEAD", "DBA"), refs, "HIGH = 主管 → DBA");
                case "CRITICAL" -> assertEquals(List.of("LEAD", "DBA", "ADMIN"), refs,
                        "CRITICAL = 主管 → DBA → 管理员");
                default -> throw new IllegalStateException("未知风险等级: " + risk);
            }
            nodesByOrder.put(refs.size(), refs);
        }
        // 节点模式：全部 OR（契约 §2.5）
        Integer nonOr = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_flow_node WHERE mode <> 'OR'", Integer.class);
        assertEquals(0, nonOr, "种子节点全部为 OR 或签");
        // 节点类型：全部 ROLE，ref = 角色 code
        Integer nonRole = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_flow_node WHERE approver_type <> 'ROLE'", Integer.class);
        assertEquals(0, nonRole, "种子节点全部为角色节点");
    }

    @Test
    void 审计链创世块_链头为64个0() {
        if (!mysqlUp) {
            return;
        }
        Map<String, Object> head = jdbc.queryForMap(
                "SELECT id, last_seq, last_hash FROM t_chain_head WHERE id=1");
        assertEquals(0L, ((Number) head.get("last_seq")).longValue());
        assertEquals("0".repeat(64), head.get("last_hash"), "创世 last_hash = 64 个 '0'");
    }
}
