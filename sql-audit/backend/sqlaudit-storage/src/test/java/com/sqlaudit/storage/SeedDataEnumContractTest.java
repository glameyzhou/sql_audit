package com.sqlaudit.storage;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApproverType;
import com.sqlaudit.common.enums.Environment;
import com.sqlaudit.common.enums.RiskLevel;
import com.sqlaudit.common.enums.RoleCode;
import com.sqlaudit.common.enums.RuleCode;
import com.sqlaudit.common.enums.RuleDialect;
import com.sqlaudit.common.enums.RuleSeverity;
import com.sqlaudit.common.enums.RuleStatementType;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 种子数据与枚举的契约测试（不连库，只读 SQL 文件）。
 *
 * <p>{@code V1}/{@code V2} 种子里的角色、规则码、环境、风险等级、审批节点等取值，必须都能被
 * 对应的枚举解析：Java 侧改成枚举后，种子 SQL 里一旦出现枚举之外的取值（或反过来，枚举改了
 * 名而种子没跟），应用启动后第一条读到该行的查询就会在 DAO 边界抛异常——这里让它在建时就变红。
 * 三种方言的种子文件逐份校验，防止只改了一份。</p>
 */
class SeedDataEnumContractTest {

    private static final List<String> DIALECT_DIRS = List.of("h2", "mysql", "postgresql");

    /** t_role 种子语句之后的部分（MySQL 写成 {@code `t_role`}）。 */
    private static final Pattern ROLE_INSERT = Pattern.compile("INSERT INTO `?t_role`?[^\\n]*\\n", Pattern.MULTILINE);

    /** t_role 种子行：('DEV', '开发', '提交工单、查询通道' */
    private static final Pattern ROLE_ROW = Pattern.compile("^\\s*\\('([A-Z]+)',\\s*'[^']*',\\s*'[^']*'", Pattern.MULTILINE);

    /** t_rule 种子行：('R_X', '方言', '语句类型', '级别', ... */
    private static final Pattern RULE_ROW = Pattern.compile(
            "^\\s*\\('([A-Z0-9_]+)',\\s*'([A-Z]+)',\\s*'([A-Z]+)',\\s*'([A-Z]+)',", Pattern.MULTILINE);

    /** t_flow_rule 种子行：('L1 全局默认 · X', '环境', 实例, '风险等级', ... */
    private static final Pattern FLOW_ROW = Pattern.compile(
            "^\\s*\\('L1 [^']*',\\s*'([A-Za-z]+)',\\s*(?:NULL|\\d+),\\s*'([A-Z]+)',", Pattern.MULTILINE);

    /** t_flow_node 种子行：SELECT id, 节点序号, '审批人类型', '审批人', '模式', ... */
    private static final Pattern NODE_ROW = Pattern.compile(
            "SELECT\\s+id,\\s*\\d+,\\s*'([A-Z_]+)',\\s*'([^']+)',\\s*'([A-Z]+)',", Pattern.MULTILINE);

    @TestFactory
    Stream<DynamicTest> seedsOnlyContainEnumValues() {
        return DIALECT_DIRS.stream().map(dir -> DynamicTest.dynamicTest(dir, () -> {
            String v1 = read("db/migration/" + dir + "/V1__init.sql");
            String v2 = read("db/migration/" + dir + "/V2__seed_rule_flow.sql");

            // 角色：恰好是内置四类
            Matcher roleInsert = ROLE_INSERT.matcher(v1);
            assertTrue(roleInsert.find(), dir + " 缺少 t_role 种子语句");
            List<String> roles = new ArrayList<>();
            Matcher m = ROLE_ROW.matcher(v1.substring(roleInsert.end()));
            while (m.find()) {
                roles.add(m.group(1));
            }
            assertEquals(Arrays.stream(RoleCode.values()).map(Enum::name).sorted().toList(),
                    roles.stream().sorted().toList(), dir + " t_role 种子");

            // 审核规则：规则码 = 内置规则全集；级别可解析；方言 / 语句类型不越界
            List<String> ruleCodes = new ArrayList<>();
            m = RULE_ROW.matcher(v2);
            while (m.find()) {
                ruleCodes.add(m.group(1));
                RuleDialect.fromCode(m.group(2));
                RuleStatementType.fromCode(m.group(3));
                RuleSeverity.fromCode(m.group(4));
            }
            assertEquals(Arrays.stream(RuleCode.values()).map(Enum::name).sorted().toList(),
                    ruleCodes.stream().sorted().toList(), dir + " t_rule 种子");

            // 审批流：环境、风险等级可解析；L1 全局默认四个风险等级各一条
            int flows = 0;
            m = FLOW_ROW.matcher(v2);
            while (m.find()) {
                Environment.fromCode(m.group(1));
                RiskLevel.fromCode(m.group(2));
                flows++;
            }
            assertEquals(RiskLevel.values().length, flows, dir + " L1 全局默认条数");

            // 审批节点：审批人类型、角色、会签 / 或签可解析（MEDIUM 1 + HIGH 2 + CRITICAL 3）
            int nodes = 0;
            m = NODE_ROW.matcher(v2);
            while (m.find()) {
                ApproverType.fromCode(m.group(1));
                RoleCode.fromCode(m.group(2));
                assertEquals(ApprovalMode.OR, ApprovalMode.fromCode(m.group(3)), dir + " 种子节点模式");
                nodes++;
            }
            assertEquals(6, nodes, dir + " L1 种子节点数");
        }));
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = SeedDataEnumContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null, "缺少种子文件: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
