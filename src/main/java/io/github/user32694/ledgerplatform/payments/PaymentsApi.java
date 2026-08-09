package io.github.user32694.ledgerplatform.payments;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentsApi {
    PaymentView topUp(TopUpCommand command);
    PaymentView transfer(TransferCommand command);
    PaymentView reverse(ReversePaymentCommand command);
    PaymentView get(UUID paymentId);
    Optional<PaymentView> findActiveReverse(UUID originalPaymentId);
    List<PaymentView> findRecent(int limit);
    List<PaymentView> findSucceededTopUps(Instant fromInclusive, Instant toInclusive);
}
