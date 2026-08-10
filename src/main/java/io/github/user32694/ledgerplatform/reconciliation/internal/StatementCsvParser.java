package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
public final class StatementCsvParser {
    private static final List<String> HEADER = List.of(
            "channel_transaction_id", "amount_cents", "occurred_at");
    private static final int MAX_ROWS = 100_000;

    public ParsedStatement parse(byte[] content) {
        String csvText = decodeUtf8(content);
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(false)
                .setTrim(false)
                .setAllowMissingColumnNames(false)
                .get();
        try (CSVParser parser = format.parse(new StringReader(csvText))) {
            if (!HEADER.equals(parser.getHeaderNames())) {
                throw new IllegalArgumentException("CSV header must be channel_transaction_id,amount_cents,occurred_at");
            }
            return parseRecords(parser);
        } catch (IOException exception) {
            throw new IllegalArgumentException("CSV cannot be read", exception);
        }
    }

    private ParsedStatement parseRecords(CSVParser parser) {
        List<ParsedStatement.Entry> entries = new ArrayList<>();
        Set<String> channelIds = new HashSet<>();
        Instant periodStart = null;
        Instant periodEnd = null;
        for (CSVRecord record : parser) {
            int lineNumber = Math.toIntExact(record.getRecordNumber() + 1);
            if (record.getRecordNumber() > MAX_ROWS) {
                throw new IllegalArgumentException("CSV exceeds 100000 data rows at line " + lineNumber);
            }
            if (record.size() != HEADER.size()) {
                throw new IllegalArgumentException("CSV line " + lineNumber + " must contain exactly 3 fields");
            }
            String channelId = record.get(0);
            if (channelId == null || channelId.isBlank()
                    || !channelId.equals(channelId.strip())
                    || channelId.codePointCount(0, channelId.length()) > 64) {
                throw new IllegalArgumentException("CSV line " + lineNumber + " channel_transaction_id is invalid");
            }
            if (!channelIds.add(channelId)) {
                throw new IllegalArgumentException("CSV line " + lineNumber + " has duplicate channel_transaction_id");
            }
            long amountCents = parseAmount(record.get(1), lineNumber);
            Instant occurredAt = parseTime(record.get(2), lineNumber);
            entries.add(new ParsedStatement.Entry(lineNumber, channelId, amountCents, occurredAt));
            if (periodStart == null || occurredAt.isBefore(periodStart)) {
                periodStart = occurredAt;
            }
            if (periodEnd == null || occurredAt.isAfter(periodEnd)) {
                periodEnd = occurredAt;
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("CSV must contain at least one data row");
        }
        return new ParsedStatement(entries, periodStart, periodEnd);
    }

    private static long parseAmount(String raw, int lineNumber) {
        try {
            long amount = Long.parseLong(raw);
            if (amount <= 0) {
                throw new NumberFormatException("not positive");
            }
            return amount;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("CSV line " + lineNumber + " amount_cents is invalid", exception);
        }
    }

    private static Instant parseTime(String raw, int lineNumber) {
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("CSV line " + lineNumber + " occurred_at is invalid", exception);
        }
    }

    private static String decodeUtf8(byte[] content) {
        if (content == null || content.length == 0) {
            return "";
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("CSV content is not valid UTF-8", exception);
        }
    }
}
