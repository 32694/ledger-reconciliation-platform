package io.github.user32694.ledgerplatform.ledger;

import java.util.List;

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
        entries = List.copyOf(entries);
        long debits = total(entries, EntrySide.DEBIT);
        long credits = total(entries, EntrySide.CREDIT);
        if (debits != credits) throw new IllegalArgumentException("Journal must be balanced");
    }

    public static Journal create(String businessReference, String type, List<JournalEntry> entries) {
        return new Journal(businessReference, type, entries);
    }

    public long totalDebits() {
        return total(entries, EntrySide.DEBIT);
    }

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
