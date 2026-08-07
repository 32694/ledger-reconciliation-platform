package io.github.user32694.ledgerplatform.payments;

import java.util.List;

public interface PaymentsApi {
    PaymentView topUp(TopUpCommand command);
    List<PaymentView> findRecent(int limit);
}
