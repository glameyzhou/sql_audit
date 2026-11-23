package com.sqlaudit.storage.impl.jdbc;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 集成测试共用的种子文件读取与语句拆分（MySQL / PG 两套 TestSupport 共享，避免两处漂移）。
 *
 * <p>拆分约定与 {@code V2__seed.sql} 文件头的书写约束一致：<strong>每条语句以行尾 {@code ;}
 * 结束，语句中间不得出现行尾分号</strong>——按 {@code ; + 换行} 切分即可安全处理
 * 字符串字面量里的中文标点（无行中分号）。</p>
 *
 * <p>{@link #replayStatements} 是清库重放的统一入口：V2 全文 + V3 及以后迁移文件的
 * <strong>DML 语句</strong>（跳过 DDL——表结构由 Flyway 保留，TRUNCATE 之后重放 DDL
 * 会撞已存在的表）。V3 起新增权限点的接口绑定行写在新迁移里（V2 冻结不可改），
 * 清库重放必须把它们一起带回来，否则端点覆盖测试会误报裸奔。</p>
 */
public final class SeedSql {

    private SeedSql() {
    }

    /** 读 classpath 下某个方言目录的 {@code V2__seed.sql} 全文（缺失 / 读失败直接抛非受检异常）。 */
    public static String readSeed(String dialectDir) {
        return readResource("db/migration/" + dialectDir + "/V2__seed.sql");
    }

    /** 按「行尾分号」拆分 SQL 文件为语句列表；注释行原样保留（交给 JDBC 执行器忽略）会破坏执行——剥掉。 */
    public static List<String> statements(String sql) {
        List<String> statements = new ArrayList<>();
        for (String chunk : sql.split(";\\r?\\n")) {
            String stripped = stripComments(chunk).strip();
            if (!stripped.isEmpty()) {
                statements.add(stripped);
            }
        }
        return statements;
    }

    /**
     * 清库重放语句清单：{@code V2__seed.sql} 全文 + V3 及以后迁移文件的 DML 语句
     * （按文件名排序，跳过 DDL）。V2 逐条重放后重建全部种子；V3+ 只补 DML——
     * 表结构已由 Flyway 建好，TRUNCATE 不动 schema。
     */
    public static List<String> replayStatements(String dialectDir) {
        List<String> statements = new ArrayList<>(statements(readSeed(dialectDir)));
        for (String file : postV2MigrationFiles(dialectDir)) {
            for (String stmt : statements(readResource(file))) {
                if (isDml(stmt)) {
                    statements.add(stmt);
                }
            }
        }
        return statements;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** classpath 下某方言目录的迁移文件，排除已冻结的 V1__schema.sql / V2__seed.sql，按名排序去重。 */
    private static List<String> postV2MigrationFiles(String dialectDir) {
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(SeedSql.class.getClassLoader());
        try {
            Resource[] resources = resolver.getResources(
                    "classpath*:db/migration/" + dialectDir + "/V*.sql");
            java.util.TreeSet<String> names = new java.util.TreeSet<>();
            for (Resource resource : resources) {
                String name = resource.getFilename();
                if (name != null && !name.equals("V1__schema.sql") && !name.equals("V2__seed.sql")) {
                    names.add(name);
                }
            }
            List<String> paths = new ArrayList<>();
            for (String name : names) {
                paths.add("db/migration/" + dialectDir + "/" + name);
            }
            return paths;
        } catch (Exception e) {
            throw new IllegalStateException("扫描迁移文件失败: db/migration/" + dialectDir, e);
        }
    }

    /** DML 判定：语句首词为 INSERT / UPDATE / DELETE / MERGE。 */
    private static boolean isDml(String stmt) {
        String first = stmt.stripLeading().split("\\s+", 2)[0].toUpperCase(java.util.Locale.ROOT);
        return Set.of("INSERT", "UPDATE", "DELETE", "MERGE").contains(first);
    }

    /** 读 classpath 资源全文（缺失 / 读失败直接抛非受检异常）。 */
    private static String readResource(String path) {
        try (InputStream in = SeedSql.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("classpath 下未找到 " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            if (e instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new IllegalStateException("读取文件失败: " + path, e);
        }
    }

    /** 去掉每行以 -- 开头的注释（种子文件只有整行注释，无行尾注释）。 */
    private static String stripComments(String chunk) {
        StringBuilder sb = new StringBuilder();
        for (String line : chunk.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (!trimmed.startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
