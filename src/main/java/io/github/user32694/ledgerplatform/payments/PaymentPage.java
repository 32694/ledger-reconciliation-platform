package io.github.user32694.ledgerplatform.payments;

import java.util.List;
import java.util.Objects;

/** One bounded page of succeeded top-up views and its next keyset position. */
public record PaymentPage(List<PaymentView> payments, PaymentPageCursor nextCursor) {
    public PaymentPage {
        Objects.requireNonNull(payments, "payments is required");
        payments = List.copyOf(payments);
    }

    /** Alias for callers that refer to the returned position as a cursor. */
    public PaymentPageCursor cursor() {
        return nextCursor;
    }
}
