package io.github.user32694.ledgerplatform.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StatementCsvParserTest {
    private final StatementCsvParser parser = new StatementCsvParser();

    @Test
    void parsesValidCsvAndNormalizesTimeToUtc() {
        var parsed = parser.parse("""
                channel_transaction_id,amount_cents,occurred_at
                CH-1,12500,2026-01-15T17:30:00+08:00
                CH-2,7500,2026-01-15T10:45:00Z
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.entries()).containsExactly(
                new ParsedStatement.Entry(2, "CH-1", 12500, Instant.parse("2026-01-15T09:30:00Z")),
                new ParsedStatement.Entry(3, "CH-2", 7500, Instant.parse("2026-01-15T10:45:00Z")));
        assertThat(parsed.periodStart()).isEqualTo(Instant.parse("2026-01-15T09:30:00Z"));
        assertThat(parsed.periodEnd()).isEqualTo(Instant.parse("2026-01-15T10:45:00Z"));
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("invalidCsv")
    void rejectsInvalidCsv(String name, byte[] content, String message) {
        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private static Stream<Arguments> invalidCsv() {
        return Stream.of(
                Arguments.of("header", csv("wrong,amount_cents,occurred_at\nCH-1,1,2026-01-01T00:00:00Z\n"), "header"),
                Arguments.of("empty file", new byte[0], "header"),
                Arguments.of("no rows", csv("channel_transaction_id,amount_cents,occurred_at\n"), "data row"),
                Arguments.of("blank id", csv("channel_transaction_id,amount_cents,occurred_at\n,1,2026-01-01T00:00:00Z\n"), "line 2"),
                Arguments.of("long id", csv("channel_transaction_id,amount_cents,occurred_at\n" + "X".repeat(65) + ",1,2026-01-01T00:00:00Z\n"), "line 2"),
                Arguments.of("zero amount", csv("channel_transaction_id,amount_cents,occurred_at\nCH-1,0,2026-01-01T00:00:00Z\n"), "amount"),
                Arguments.of("negative amount", csv("channel_transaction_id,amount_cents,occurred_at\nCH-1,-1,2026-01-01T00:00:00Z\n"), "amount"),
                Arguments.of("non-numeric amount", csv("channel_transaction_id,amount_cents,occurred_at\nCH-1,nope,2026-01-01T00:00:00Z\n"), "amount"),
                Arguments.of("overflow amount", csv("channel_transaction_id,amount_cents,occurred_at\nCH-1,9223372036854775808,2026-01-01T00:00:00Z\n"), "amount"),
                Arguments.of("invalid time", csv("channel_transaction_id,amount_cents,occurred_at\nCH-1,1,nope\n"), "occurred_at"),
                Arguments.of("missing field", csv("channel_transaction_id,amount_cents,occurred_at\nCH-1,1\n"), "line 2"),
                Arguments.of("extra field", csv("channel_transaction_id,amount_cents,occurred_at\nCH-1,1,2026-01-01T00:00:00Z,extra\n"), "line 2"),
                Arguments.of("empty row", csv("channel_transaction_id,amount_cents,occurred_at\n\n"), "line 2"),
                Arguments.of("duplicate id", csv("channel_transaction_id,amount_cents,occurred_at\nCH-1,1,2026-01-01T00:00:00Z\nCH-1,2,2026-01-01T00:00:00Z\n"), "duplicate"),
                Arguments.of("leading whitespace", csv("channel_transaction_id,amount_cents,occurred_at\n CH-1,1,2026-01-01T00:00:00Z\n"), "channel_transaction_id"),
                Arguments.of("malformed utf8", new byte[] {(byte) 0xc3, (byte) 0x28}, "UTF-8"));
    }

    @Test
    void rejectsMoreThanOneHundredThousandRows() {
        var builder = new StringBuilder("channel_transaction_id,amount_cents,occurred_at\n");
        for (int index = 1; index <= 100_001; index++) {
            builder.append("CH-").append(index).append(",1,2026-01-01T00:00:00Z\n");
        }

        assertThatThrownBy(() -> parser.parse(builder.toString().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CSV exceeds 100000 data rows at line 100002");
    }

    private static byte[] csv(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
