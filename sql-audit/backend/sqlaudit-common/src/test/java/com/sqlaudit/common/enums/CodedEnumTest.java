package com.sqlaudit.common.enums;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.net.URL;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodedEnum} 契约测试：所有带存储码的枚举都必须满足同一套规则。
 *
 * <p>存储码是 DB 列、前端展示、接口 JSON 共用的契约（工单 / 执行单元 / 回滚状态是小写，
 * 用户 / 实例状态是大写，环境是 prod 与 ALL 混用），这里把口径锁死：新增枚举只要实现了
 * {@link CodedEnum}，就会被自动纳入检查。</p>
 */
class CodedEnumTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 包下所有实现了 {@link CodedEnum} 的枚举（自动发现，新增枚举无需登记）。 */
    static List<Class<? extends Enum<?>>> codedEnums() throws IOException {
        String pkg = CodedEnum.class.getPackageName();
        Enumeration<URL> roots = CodedEnum.class.getClassLoader().getResources(pkg.replace('.', '/'));
        List<Class<? extends Enum<?>>> result = new ArrayList<>();
        while (roots.hasMoreElements()) {
            URL root = roots.nextElement();
            if (!"file".equals(root.getProtocol())) {
                continue; // 测试从 target/classes 目录运行；非目录形态（jar）不在此发现
            }
            String[] names = new java.io.File(root.getPath()).list((d, n) -> n.endsWith(".class"));
            if (names == null) {
                continue;
            }
            for (String name : Arrays.stream(names).sorted().toList()) {
                Class<?> c;
                try {
                    c = Class.forName(pkg + "." + name.substring(0, name.length() - ".class".length()));
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException(e);
                }
                if (c.isEnum() && CodedEnum.class.isAssignableFrom(c)) {
                    @SuppressWarnings("unchecked")
                    Class<? extends Enum<?>> enumClass = (Class<? extends Enum<?>>) c;
                    result.add(enumClass);
                }
            }
        }
        return List.copyOf(result);
    }

    @Test
    void codedEnumsAreDiscovered() throws IOException {
        List<Class<? extends Enum<?>>> found = codedEnums();
        assertTrue(found.size() >= 15, "应自动发现带存储码的枚举，实际: " + found);
        assertTrue(found.contains(TicketStatus.class));
        assertTrue(found.contains(UserStatus.class));
        // 纯内部标识、不落库的枚举不实现 CodedEnum
        assertFalse(found.contains(UnavailableReason.class));
        assertFalse(found.contains(DangerousClause.class));
    }

    @TestFactory
    Stream<DynamicTest> storageCodesAreUniquePerEnum() throws IOException {
        return codedEnums().stream().map(type -> DynamicTest.dynamicTest(type.getSimpleName(), () -> {
            Set<String> codes = new HashSet<>();
            for (Enum<?> constant : type.getEnumConstants()) {
                String code = ((CodedEnum) constant).getCode();
                assertTrue(code != null && !code.isBlank(), type.getSimpleName() + "." + constant + " 存储码为空");
                assertTrue(codes.add(code), type.getSimpleName() + " 存储码重复: " + code);
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> fromCodeRoundTripsCaseInsensitively() throws IOException {
        return codedEnums().stream().map(type -> DynamicTest.dynamicTest(type.getSimpleName(), () -> {
            for (Enum<?> constant : type.getEnumConstants()) {
                String code = ((CodedEnum) constant).getCode();
                assertSame(constant, fromCode(type, code), "精确匹配");
                assertSame(constant, fromCode(type, code.toUpperCase()), "大小写不敏感");
                assertSame(constant, fromCode(type, code.toLowerCase()), "大小写不敏感");
                assertSame(constant, fromCode(type, "  " + code + " \t"), "忽略首尾空白");
            }
            assertNull(fromCode(type, null), "null 透传");
        }));
    }

    @TestFactory
    Stream<DynamicTest> fromCodeRejectsUnknownValues() throws IOException {
        return codedEnums().stream().map(type -> DynamicTest.dynamicTest(type.getSimpleName(), () -> {
            String valid = Arrays.stream(type.getEnumConstants())
                    .map(c -> ((CodedEnum) c).getCode()).collect(Collectors.toList()).toString();
            for (String bad : List.of("", "no_such_value", "actve", "null", valid + "x")) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> fromCode(type, bad), type.getSimpleName() + " 不应接受: \"" + bad + "\"");
                assertTrue(e.getMessage().contains(type.getSimpleName()), "报错要指明是哪个枚举: " + e.getMessage());
                assertTrue(e.getMessage().contains(valid), "报错要列出全部合法值: " + e.getMessage());
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> jacksonUsesStorageCodeOnTheWire() throws IOException {
        return codedEnums().stream().map(type -> DynamicTest.dynamicTest(type.getSimpleName(), () -> {
            for (Enum<?> constant : type.getEnumConstants()) {
                String code = ((CodedEnum) constant).getCode();
                // 序列化：输出存储码而不是枚举名（小写存储码的枚举两者不同）
                assertEquals(JSON.writeValueAsString(code), JSON.writeValueAsString(constant));
                assertEquals("{\"v\":" + JSON.writeValueAsString(code) + "}",
                        JSON.writeValueAsString(Map.of("v", constant)));
                // 反序列化：按存储码还原
                assertSame(constant, JSON.readValue("\"" + code + "\"", type.asSubclass(Enum.class)));
            }
            // 未知取值：拒绝而不是静默变成 null
            assertThrows(IOException.class, () -> JSON.readValue("\"no_such_value\"", type.asSubclass(Enum.class)));
        }));
    }

    /** 存储码契约：DB 已有数据、前端已按这些字符串展示，一旦上线不可改。 */
    @Test
    void wireCodesAreLocked() {
        assertEquals(List.of("draft", "submitted", "auditing", "approved", "rejected", "scheduled", "executing",
                        "succeeded", "partially_succeeded", "failed", "needs_confirm", "closed", "cancelled", "expired"),
                codes(TicketStatus.class));
        assertEquals(List.of("pending", "leased", "executing", "success", "failed", "skipped", "needs_confirm"),
                codes(UnitStatus.class));
        assertEquals(List.of("generated", "verified", "applied", "expired", "not_available", "failed", "partial"),
                codes(RollbackStatus.class));
        assertEquals(List.of("FULL", "PARTIAL", "NONE"), codes(RollbackCoverage.class));
        assertEquals(List.of("ACTIVE", "DISABLED"), codes(UserStatus.class));
        assertEquals(List.of("ACTIVE", "DISABLED"), codes(InstanceStatus.class));
        assertEquals(List.of("ALL", "SELECTED"), codes(InstanceScope.class));
        assertEquals(List.of("WEWORK", "LOCAL"), codes(UserSource.class));
        assertEquals(List.of("RO_META", "RO_DATA", "RW_EXEC"), codes(AccountType.class));
        assertEquals(List.of("MIRROR", "REVERSE_DDL", "BINLOG", "SNAPSHOT", "NONE"), codes(RollbackGenerator.class));
        assertEquals(List.of("AND", "OR"), codes(ApprovalMode.class));
        assertEquals(List.of("PENDING", "APPROVED", "REJECTED", "TRANSFERRED", "SKIPPED"),
                codes(ApprovalResult.class));
        assertEquals(List.of("USER", "ROLE", "INSTANCE_OWNER"), codes(ApproverType.class));
        assertEquals(List.of("MYSQL", "PG", "IOTDB"), codes(Dialect.class));
        assertEquals(List.of("LOW", "MEDIUM", "HIGH", "CRITICAL"), codes(RiskLevel.class));
        assertEquals(List.of("ERROR", "WARN"), codes(RuleSeverity.class));
        assertEquals(List.of("SELECT", "INSERT", "UPDATE", "DELETE", "DDL", "OTHER"), codes(StatementType.class));
        // 规则配置用的「不限」取值：ALL 表示不限，与 StatementType/Dialect 不同
        assertEquals(List.of("MYSQL", "PG", "IOTDB", "ALL"), codes(RuleDialect.class));
        assertEquals(List.of("SELECT", "INSERT", "UPDATE", "DELETE", "DDL", "ALL"), codes(RuleStatementType.class));
        // 审计动作：统一大写下划线命名
        assertEquals(List.of("LOGIN", "USER_CREATE", "USER_UPDATE", "PASSWORD_RESET", "ROLE_ASSIGN",
                        "PERMISSION_ASSIGN", "ROLE_CREATE", "ROLE_UPDATE", "ROLE_DELETE",
                        "INSTANCE_CREATE", "INSTANCE_UPDATE", "INSTANCE_DELETE", "RULE_CREATE", "RULE_UPDATE",
                        "RULE_DELETE", "FLOW_CREATE", "FLOW_UPDATE", "FLOW_DELETE", "QUERY_EXECUTED",
                        "TICKET_STATE_CHANGE", "UNIT_EXEC", "ROLLBACK_EXECUTE", "ROLLBACK_STATEMENT_APPLIED",
                        "TABLE_REMARK_UPDATE"),
                codes(AuditAction.class));
        assertEquals(List.of("user", "role", "instance", "rule", "flow_rule", "ticket", "TICKET_UNIT", "SESSION",
                        "rollback", "rollback_statement", "permission", "table_remark"),
                codes(AuditTargetType.class));
        assertEquals(List.of("SUCCESS", "REJECTED", "FAILED", "PARTIAL"), codes(AuditResult.class));
        // 门禁码沿用历史口径：两道门直接借用规则码
        assertEquals(List.of("APPROVAL_TTL", "R_SCHEMA_DRIFT", "R_ROLLBACK_UNAVAILABLE"), codes(GateCode.class));
        // 错误码是前端分流用的契约（如 TICKET_NOT_FOUND）
        assertEquals(List.of("BUSINESS_ERROR", "UNAUTHORIZED", "FORBIDDEN", "INVALID_REQUEST",
                        "ILLEGAL_STATE_TRANSITION", "INTERNAL_ERROR", "CREDENTIAL_CRYPTO", "INSTANCE_NOT_FOUND",
                        "IOTDB_NOT_SUPPORTED", "RO_META_CREDENTIAL_MISSING", "RO_META_CREDENTIAL_INVALID",
                        "META_QUERY_FAILED", "USERNAME_EXISTS", "USER_NOT_FOUND", "ROLE_NOT_FOUND",
                        "BUILTIN_ROLE_IMMUTABLE", "RBAC_LOCKOUT", "EMPTY_UNITS", "EMPTY_INSTANCES",
                        "ERROR_RULE_BLOCKED", "IMPACT_ASSESSMENT_INCOMPLETE", "FINGERPRINT_CAPTURE_FAILED",
                        "TICKET_STATE_CONFLICT", "INSTANCE_OUT_OF_SCOPE", "SQL_TOO_LARGE",
                        "SCHEMA_OUT_OF_BOUND",
                        "TICKET_NOT_FOUND", "TICKET_NOT_AUDITING", "TICKET_ACCESS_DENIED",
                        "APPROVAL_FLOW_NOT_CONFIGURED", "NO_ELIGIBLE_APPROVER", "DUPLICATE_FLOW",
                        "FLOW_NOT_FOUND", "FLOW_NODE_INVALID", "APPROVAL_NODES_EXIST",
                        "SELF_APPROVAL_FORBIDDEN", "SELF_CONFIRM_FORBIDDEN", "APPROVAL_ALREADY_DECIDED",
                        "APPROVAL_TARGET_NOT_FOUND", "NO_PENDING_APPROVAL", "ROLLBACK_NOT_FOUND",
                        "ROLLBACK_INVALID_STATUS", "ROLLBACK_NOT_EXECUTABLE", "ROLLBACK_EXPIRED", "ROLLBACK_NO_UNIT",
                        "ROLLBACK_NO_INSTANCE", "ROLLBACK_PARAM_PARSE_FAILED"), codes(ErrorCode.class));
    }

    /** 小写存储码（工单状态）精确匹配优先、大小写不敏感兜底，非法取值必须被拒而不是静默落库。 */
    @Test
    void mixedCaseCodesResolveAndUnknownIsRejected() {
        assertEquals(TicketStatus.DRAFT, TicketStatus.fromCode("draft"));
        assertEquals(TicketStatus.DRAFT, TicketStatus.fromCode("DRAFT"));
        assertEquals(InstanceScope.ALL, InstanceScope.fromCode("ALL"));
        assertEquals(InstanceScope.SELECTED, InstanceScope.fromCode("selected"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> InstanceScope.fromCode("some"));
        assertTrue(e.getMessage().contains("[ALL, SELECTED]"), e.getMessage());
    }

    /** 去环境化（2026-11-23）：业务层不再有 environment 维度，对应枚举已删除。 */
    @Test
    void environmentEnumIsGone() throws IOException {
        assertTrue(codedEnums().stream().map(Class::getSimpleName).noneMatch(
                n -> n.equals("Environment") || n.equals("RoleCode") || n.equals("PermissionCode")),
                "Environment / RoleCode / PermissionCode 必须已从枚举包移除");
    }

    @Test
    void codeHelperIsNullSafe() {
        assertNull(CodedEnum.code(null));
        assertEquals("draft", CodedEnum.code(TicketStatus.DRAFT));
    }

    private static List<String> codes(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(c -> ((CodedEnum) c).getCode()).toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum<?> fromCode(Class<? extends Enum<?>> type, String code) {
        return CodedEnum.fromCode((Class) type, code);
    }
}
