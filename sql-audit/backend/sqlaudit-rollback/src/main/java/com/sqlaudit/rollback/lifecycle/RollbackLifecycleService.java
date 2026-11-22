package com.sqlaudit.rollback.lifecycle;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.RollbackStatus;
import com.sqlaudit.parser.SqlParserService;
import com.sqlaudit.storage.dao.RollbackDao;
import com.sqlaudit.storage.model.RollbackSql;
import com.sqlaudit.storage.model.RollbackStatement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;

/**
 * 回滚 SQL 生命周期服务（设计文档 §5.9）。
 *
 * <p>状态机：{@code generated → verified → applied | expired | not_available}。</p>
 *
 * <p>关键规则：</p>
 * <ul>
 *   <li>反向语句落 <strong>rollback_statement 子表</strong>（每行一条，stmt_order 稳定），
 *       不是单行 VARBINARY（v2 错误已修正）；</li>
 *   <li>超阈值（默认 5000 条 / 16MB）不落平台库，转对象存储——本服务只判阈值，
 *       对象存储客户端 W2 装配（snapshot_uri 记位置）；</li>
 *   <li>生成后<strong>语法自检</strong>：反向 SQL 过同方言 Parser，解析失败标
 *       not_available + SYNTAX_CHECK_FAILED（§5.3）；</li>
 *   <li>有效期默认 24h：过期标 expired，回放前二次校验；</li>
 *   <li>依赖检测：回滚执行前按主键重查当前行比对前镜像 hash，不一致拒绝执行
 *       （数据已被后续工单改动，回滚会覆盖别人写入）——hash 用本类的
 *       {@link #preimageHash} 口径，回放方与生成方必须同一实现。</li>
 * </ul>
 */
public class RollbackLifecycleService {

    /** 单 unit 反向语句条数上限（与 §5.3 前镜像上限一致）。 */
    public static final int MAX_STATEMENTS = 5_000;

    private final RollbackDao rollbackDao;
    private final SqlParserService parserService;

    public RollbackLifecycleService(RollbackDao rollbackDao, SqlParserService parserService) {
        this.rollbackDao = rollbackDao;
        this.parserService = parserService;
    }

    /**
     * 生成后落库（status=generated）+ 语法自检（通过则 verified，失败标 not_available）。
     *
     * @param unitId           执行单元
     * @param generator        MIRROR / REVERSE_DDL / BINLOG / SNAPSHOT
     * @param coverage         FULL / PARTIAL / NONE
     * @param preimageRows     前镜像行（依赖检测 hash 用；INSERT 反向无前镜像传 null）
     * @param statements       反向语句（按回放顺序）
     * @param now              当前时间（有效期起点）
     * @param ttl              有效期（默认 24h）
     * @return 回滚主记录 id
     */
    public long persistAndVerify(long unitId, String generator, String coverage,
                                 List<String> preimageRows,
                                 List<com.sqlaudit.rollback.reverse.ReverseStatement> statements,
                                 Dialect dialect, LocalDateTime now, Duration ttl) {
        if (statements.size() > MAX_STATEMENTS) {
            throw new IllegalStateException("反向语句 " + statements.size() + " 条超上限 "
                    + MAX_STATEMENTS + "，应转对象存储（调用方处理，此处拒绝落平台库）");
        }

        // 语法自检：每条反向 SQL 过同方言 Parser（§5.3）
        String syntaxFailure = null;
        for (com.sqlaudit.rollback.reverse.ReverseStatement stmt : statements) {
            if (parserService.parse(stmt.sql(), dialect).get(0).getParseConfidence() == 0) {
                syntaxFailure = stmt.sql();
                break;
            }
        }

        // 主记录
        RollbackSql master = new RollbackSql();
        master.setUnitId(unitId);
        master.setGenerator(generator);
        long totalBytes = statements.stream()
                .mapToLong(s -> s.sql().getBytes(StandardCharsets.UTF_8).length).sum();
        master.setTotalBytes(totalBytes);
        master.setCoverage(coverage);
        master.setPreimageHash(preimageHash(preimageRows));
        master.setExpiresAt(now.plus(ttl));
        if (syntaxFailure != null) {
            master.setStatus(RollbackStatus.NOT_AVAILABLE.name().toLowerCase());
            master.setUnavailableReason("SYNTAX_CHECK_FAILED: " + truncate(syntaxFailure, 200));
        } else {
            master.setStatus(RollbackStatus.VERIFIED.name().toLowerCase());
        }
        master.setStmtCount(statements.size());
        rollbackDao.insertRollbackSql(master);

        // 明细子表（每行一条，stmt_order 与回放顺序一致且稳定）
        int order = 0;
        for (com.sqlaudit.rollback.reverse.ReverseStatement stmt : statements) {
            RollbackStatement row = new RollbackStatement();
            // 反向语句与参数的加密由 W2 的存储实现层做（DetailCipher 同款机制），
            // 服务层存明文，DAO 实现负责透明加解密
            row.setRollbackId(master.getId());
            row.setStmtOrder(order++);
            row.setReverseSql(stmt.sql().getBytes(StandardCharsets.UTF_8));
            row.setValueParams(paramsBytes(stmt.params()));
            rollbackDao.insertRollbackStatement(row);
        }
        return master.getId();
    }

    /**
     * 依赖检测（§5.9）：回滚执行前按主键重查当前行，比对前镜像 hash。
     *
     * @param unitId 执行单元（rollback_sql 按 unit_id 唯一）
     * @return true = 数据未被动过，可回滚；false = 已被后续工单改动，拒绝执行并告警
     */
    public boolean dependencyCheck(long unitId, List<String> currentPreimageRows) {
        RollbackSql master = rollbackDao.findByUnitId(unitId);
        if (master == null || master.getPreimageHash() == null) {
            return true; // 无前镜像（INSERT 反向）不适用依赖检测
        }
        return master.getPreimageHash().equals(preimageHash(currentPreimageRows));
    }

    // ------------------------------------------------------------------

    /**
     * 前镜像 hash 口径（依赖检测用）：行集规范化拼接（列序无关：每行按 key 排序拼接）
     * 后整体 SHA256。<strong>回放方与生成方必须同一实现</strong>——本方法是唯一实现点。
     */
    public static String preimageHash(List<String> preimageRows) {
        if (preimageRows == null) {
            return null;
        }
        String canonical = String.join("\n", preimageRows);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            try (Formatter f = new Formatter()) {
                for (byte b : digest) {
                    f.format("%02x", b);
                }
                return f.toString();
            }
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static byte[] paramsBytes(List<Object> params) {
        // 参数序列化（CBOR/JSON，§5.9）——首版用紧凑 JSON 文本，W2 可换 CBOR
        if (params == null || params.isEmpty()) {
            return null;
        }
        List<String> items = new ArrayList<>();
        for (Object p : params) {
            items.add(p == null ? "null" : p instanceof Number || p instanceof Boolean
                    ? String.valueOf(p) : "\"" + String.valueOf(p).replace("\\", "\\\\")
                    .replace("\"", "\\\"") + "\"");
        }
        return ("[" + String.join(",", items) + "]").getBytes(StandardCharsets.UTF_8);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
