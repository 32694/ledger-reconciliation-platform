package io.github.user32694.ledgerplatform.payments.internal;

import io.github.user32694.ledgerplatform.payments.PaymentView;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.payments.ReversePaymentCommand;
import io.github.user32694.ledgerplatform.payments.TopUpCommand;
import io.github.user32694.ledgerplatform.payments.TransferCommand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
class PaymentsFacade implements PaymentsApi {
    private final PaymentSubmissionService submission;
    private final PaymentProcessor processor;
    private final PaymentInstructionRepository repository;

    PaymentsFacade(
            PaymentSubmissionService submission,
            PaymentProcessor processor,
            PaymentInstructionRepository repository) {
        this.submission = submission;
        this.processor = processor;
        this.repository = repository;
    }

    @Override
    public PaymentView topUp(TopUpCommand command) {
        var paymentId = submission.submit(command);
        try {
            return toView(processor.process(paymentId));
        } catch (PaymentProcessor.PaymentRejectedException exception) {
            return toView(processor.fail(exception.paymentId(), exception.reason()));
        }
    }

    @Override
    public PaymentView transfer(TransferCommand command) {
        var paymentId = submission.submit(command);
        try {
            return toView(processor.process(paymentId));
        } catch (PaymentProcessor.PaymentRejectedException exception) {
            return toView(processor.fail(exception.paymentId(), exception.reason()));
        }
    }

    @Override
    public PaymentView reverse(ReversePaymentCommand command) {
        throw new UnsupportedOperationException("Reverse payment processing is not implemented");
    }

    @Override
    public PaymentView get(UUID paymentId) {
        return repository.findById(paymentId)
                .map(PaymentsFacade::toView)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Payment does not exist: " + paymentId));
    }

    @Override
    public Optional<PaymentView> findActiveReverse(UUID originalPaymentId) {
        return repository.findActiveReverse(originalPaymentId).map(PaymentsFacade::toView);
    }

    @Override
    public List<PaymentView> findRecent(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Limit must be between 1 and 100");
        }
        return repository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, limit)).stream()
                .map(PaymentsFacade::toView)
                .toList();
    }

    @Override
    public List<PaymentView> findSucceededTopUps(Instant fromInclusive, Instant toInclusive) {
        if (fromInclusive == null || toInclusive == null) {
            throw new IllegalArgumentException("Range is required");
        }
        if (fromInclusive.isAfter(toInclusive)) {
            throw new IllegalArgumentException("Start must not be after end");
        }
        return repository.findSucceededTopUps(fromInclusive, toInclusive).stream()
                .map(PaymentsFacade::toView)
                .toList();
    }

    private static PaymentView toView(PaymentInstructionEntity payment) {
        return new PaymentView(
                payment.id(),
                payment.channelReference(),
                payment.paymentType(),
                payment.payerAccountId(),
                payment.payeeAccountId(),
                payment.amountCents(),
                payment.status(),
                payment.failureReason(),
                payment.originalPaymentId(),
                payment.operationReason(),
                payment.occurredAt());
    }
}
