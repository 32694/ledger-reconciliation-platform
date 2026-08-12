package io.github.user32694.ledgerplatform.ledger;

import java.util.List;

/**
 * 一次不可变账务交易的提交请求。
 *
 * <p>所有借方和贷方必须在同一请求中提交，并且借贷合计相等。
 */
public record Journal(String businessReference, String type, List<JournalEntry> entries) {
    public Journal {
        if (businessReference == null || businessReference.isBlank()) {
            throw new IllegalArgumentException("Business reference is required");
        }
        if (businessReference.length() > 128) {
            throw new IllegalArgumentException("Business reference must not exceed 128 characters");
        }
        if (!"TOP_UP".equals(type)
                && !"TRANSFER".equals(type)
                && !"REFUND".equals(type)
                && !"REVERSAL".equals(type)) {
            throw new IllegalArgumentException("Unsupported journal type");
        }
        if (entries == null || entries.size() < 2) {
            throw new IllegalArgumentException("A journal requires at least two entries");
        }
        // 防止调用方在 journal 创建后继续修改原始 List，破坏审计事实。
        entries = List.copyOf(entries);
        long debits = total(entries, EntrySide.DEBIT);
        long credits = total(entries, EntrySide.CREDIT);
        if (debits != credits) throw new IllegalArgumentException("Journal must be balanced");
    }

    /** 创建并立即校验一个 journal。 */
    public static Journal create(String businessReference, String type, List<JournalEntry> entries) {
        return new Journal(businessReference, type, entries);
    }

    /** 返回借方总额，单位为分。 */
    public long totalDebits() {
        return total(entries, EntrySide.DEBIT);
    }

    /** 返回贷方总额，单位为分。 */
    public long totalCredits() {
        return total(entries, EntrySide.CREDIT);
    }

    private static long total(List<JournalEntry> entries, EntrySide side) {
        long total = 0;
        try {
            for (JournalEntry entry : entries) {
                if (entry.side() == side) {
                    total = Math.addExact(total, entry.money().cents());
                }
            }
            return total;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Journal amount exceeds supported range", exception);
        }
    }
}
