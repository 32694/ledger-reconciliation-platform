package io.github.user32694.ledgerplatform.ledger;

import java.util.List;

public record Journal(String businessReference, String type, List<JournalEntry> entries) {
    public Journal {
        if (businessReference == null || businessReference.isBlank()) {
            throw new IllegalArgumentException("Business reference is required");
        }
        if (entries == null || entries.size() < 2) {
            throw new IllegalArgumentException("A journal requires at least two entries");
        }
        entries = List.copyOf(entries);
        long debits = entries.stream()
                .filter(entry -> entry.side() == EntrySide.DEBIT)
                .mapToLong(entry -> entry.money().cents())
                .sum();
        long credits = entries.stream()
                .filter(entry -> entry.side() == EntrySide.CREDIT)
                .mapToLong(entry -> entry.money().cents())
                .sum();
        if (debits != credits) throw new IllegalArgumentException("Journal must be balanced");
    }

    public static Journal create(String businessReference, String type, List<JournalEntry> entries) {
        return new Journal(businessReference, type, entries);
    }

    public long totalDebits() {
        return entries.stream().filter(e -> e.side() == EntrySide.DEBIT)
                .mapToLong(e -> e.money().cents()).sum();
    }

    public long totalCredits() {
        return entries.stream().filter(e -> e.side() == EntrySide.CREDIT)
                .mapToLong(e -> e.money().cents()).sum();
    }
}
