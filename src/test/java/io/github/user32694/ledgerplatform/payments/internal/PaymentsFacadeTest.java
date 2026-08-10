package io.github.user32694.ledgerplatform.payments.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentsFacadeTest {
    @Test
    void rejectsDuplicateReferencesReturnedByRepository() {
        var repository = mock(PaymentInstructionRepository.class);
        var first = payment("duplicate-reference", UUID.randomUUID());
        var second = payment("duplicate-reference", UUID.randomUUID());
        when(repository.findSucceededTopUpsByReferences(
                Set.of("duplicate-reference"), Instant.EPOCH, Instant.EPOCH))
                .thenReturn(List.of(first, second));
        var facade = new PaymentsFacade(
                mock(PaymentSubmissionService.class), mock(PaymentProcessor.class), repository);

        assertThatThrownBy(() -> facade.findSucceededTopUpsByReferences(
                Set.of("duplicate-reference"), Instant.EPOCH, Instant.EPOCH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Duplicate payment reference: duplicate-reference");
    }

    @Test
    void skipsRepositoryForEmptyReferences() {
        var repository = mock(PaymentInstructionRepository.class);
        var facade = new PaymentsFacade(
                mock(PaymentSubmissionService.class), mock(PaymentProcessor.class), repository);

        assertThatCode(() -> facade.findSucceededTopUpsByReferences(
                Set.of(), Instant.EPOCH, Instant.EPOCH)).doesNotThrowAnyException();
        verifyNoInteractions(repository);
    }

    private static PaymentInstructionEntity payment(String reference, UUID id) {
        var payment = mock(PaymentInstructionEntity.class);
        when(payment.id()).thenReturn(id);
        when(payment.channelReference()).thenReturn(reference);
        when(payment.paymentType()).thenReturn("TOP_UP");
        when(payment.payeeAccountId()).thenReturn(UUID.randomUUID());
        when(payment.amountCents()).thenReturn(100L);
        when(payment.status()).thenReturn("SUCCEEDED");
        when(payment.occurredAt()).thenReturn(Instant.EPOCH);
        return payment;
    }
}
