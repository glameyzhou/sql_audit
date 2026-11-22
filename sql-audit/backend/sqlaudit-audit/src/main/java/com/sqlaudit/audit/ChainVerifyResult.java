package com.sqlaudit.audit;

/**
 * 链校验结果（设计文档 §7.3「定期校验任务比对链完整性，断裂即告警」）。
 *
 * <p>不抛异常：任何断裂（prev_hash 不衔接 / hash 重算不一致 / detail 解密失败）
 * 都以 {@code ok=false} + 首个断裂 seq + 原因返回，由调用方决定告警动作。</p>
 */
public final class ChainVerifyResult {

    private final boolean ok;
    /** 已校验的记录条数（含断裂那条） */
    private final long checkedCount;
    /** 第一处断裂的 seq；ok=true 时为 null */
    private final Long breakSeq;
    /** 断裂原因（人读中文）；ok=true 时为 null */
    private final String reason;

    private ChainVerifyResult(boolean ok, long checkedCount, Long breakSeq, String reason) {
        this.ok = ok;
        this.checkedCount = checkedCount;
        this.breakSeq = breakSeq;
        this.reason = reason;
    }

    /** 全链通过。 */
    static ChainVerifyResult ok(long checkedCount) {
        return new ChainVerifyResult(true, checkedCount, null, null);
    }

    /** 在 seq 处断裂。 */
    static ChainVerifyResult breakAt(long checkedCount, long seq, String reason) {
        return new ChainVerifyResult(false, checkedCount, seq, reason);
    }

    public boolean isOk() {
        return ok;
    }

    public long getCheckedCount() {
        return checkedCount;
    }

    public Long getBreakSeq() {
        return breakSeq;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        if (ok) {
            return "ChainVerifyResult{ok=true, checked=" + checkedCount + "}";
        }
        return "ChainVerifyResult{ok=false, breakSeq=" + breakSeq + ", reason=" + reason + "}";
    }
}
