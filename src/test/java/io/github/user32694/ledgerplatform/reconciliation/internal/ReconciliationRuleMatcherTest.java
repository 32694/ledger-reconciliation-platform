package io.github.user32694.ledgerplatform.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconciliationRuleMatcherTest {
    private final ReconciliationRuleMatcher matcher = new ReconciliationRuleMatcher();

    @Test
    void createsChannelOnlyWhenNoExactPaymentExists() {
        var item = statement(100, Optional.empty());

        assertThat(matcher.process(item, 0))
                .isEqualTo(new ReconciliationWorkResult(
                        item.statementEntryId(), null, ResultType.CHANNEL_ONLY, ResolutionStatus.OPEN));
    }

    @Test
    void matchesWhenAmountDifferenceIsBelowOrEqualToTolerance() {
        var payment = payment(101, false);

        assertThat(matcher.process(statement(100, Optional.of(payment)), 2).resultType())
                .isEqualTo(ResultType.MATCHED);
        assertThat(matcher.process(statement(100, Optional.of(payment)), 1))
                .isEqualTo(new ReconciliationWorkResult(
                        statementId(), payment.paymentId(), ResultType.MATCHED, ResolutionStatus.NOT_REQUIRED));
    }

    @Test
    void createsAmountMismatchWhenDifferenceExceedsTolerance() {
        var payment = payment(102, false);

        assertThat(matcher.process(statement(100, Optional.of(payment)), 1))
                .isEqualTo(new ReconciliationWorkResult(
                        statementId(), payment.paymentId(), ResultType.AMOUNT_MISMATCH, ResolutionStatus.OPEN));
    }

    @Test
    void handlesTheFullPositiveLongRangeWithoutOverflow() {
        var payment = payment(Long.MAX_VALUE, false);

        assertThat(matcher.process(statement(1, Optional.of(payment)), Long.MAX_VALUE - 1).resultType())
                .isEqualTo(ResultType.MATCHED);
        assertThat(matcher.process(statement(1, Optional.of(payment)), Long.MAX_VALUE - 2).resultType())
                .isEqualTo(ResultType.AMOUNT_MISMATCH);
    }

    @Test
    void createsInternalOnlyForAnUnconsumedPayment() {
        var payment = payment(100, false);

        assertThat(matcher.process(payment, 0))
                .isEqualTo(new ReconciliationWorkResult(
                        null, payment.paymentId(), ResultType.INTERNAL_ONLY, ResolutionStatus.OPEN));
    }

    @Test
    void skipsAConsumedPayment() {
        assertThat(matcher.process(payment(100, true), 0)).isNull();
    }

    private static ReconciliationWorkItem.Statement statement(
            long amountCents, Optional<ReconciliationWorkItem.Payment> payment) {
        return new ReconciliationWorkItem.Statement(statementId(), amountCents, payment);
    }

    private static ReconciliationWorkItem.Payment payment(long amountCents, boolean consumed) {
        return new ReconciliationWorkItem.Payment(UUID.fromString(
                "00000000-0000-0000-0000-000000000002"), amountCents, consumed);
    }

    private static UUID statementId() {
        return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }
}
