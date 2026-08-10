package io.github.user32694.ledgerplatform.payments.internal;

import io.github.user32694.ledgerplatform.payments.PaymentView;
import io.github.user32694.ledgerplatform.payments.PaymentPage;
import io.github.user32694.ledgerplatform.payments.PaymentPageCursor;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.payments.ReversePaymentCommand;
import io.github.user32694.ledgerplatform.payments.TopUpCommand;
import io.github.user32694.ledgerplatform.payments.TransferCommand;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        var paymentId = submission.submit(command);
        try {
            return toView(processor.process(paymentId));
        } catch (PaymentProcessor.PaymentRejectedException exception) {
            return toView(processor.fail(exception.paymentId(), exception.reason()));
        }
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
        validateRange(fromInclusive, toInclusive);
        return repository.findSucceededTopUps(fromInclusive, toInclusive).stream()
                .map(PaymentsFacade::toView)
                .toList();
    }

    @Override
    public Map<String, PaymentView> findSucceededTopUpsByReferences(
            Set<String> references, Instant fromInclusive, Instant toInclusive) {
        validateRange(fromInclusive, toInclusive);
        if (references == null) {
            throw new IllegalArgumentException("References are required");
        }
        var result = new LinkedHashMap<String, PaymentView>();
        if (references.isEmpty()) {
            return result;
        }
        for (var payment : repository.findSucceededTopUpsByReferences(
                references, fromInclusive, toInclusive)) {
            var view = toView(payment);
            if (result.putIfAbsent(view.channelReference(), view) != null) {
                throw new IllegalStateException(
                        "Duplicate payment reference: " + view.channelReference());
            }
        }
        return result;
    }

    @Override
    public PaymentPage findSucceededTopUpsAfter(
            Instant fromInclusive, Instant toInclusive, PaymentPageCursor after, int limit) {
        validateRange(fromInclusive, toInclusive);
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Limit must be between 1 and 500");
        }
        var pageRequest = PageRequest.of(0, limit);
        var entities = after == null
                ? repository.findSucceededTopUpsPage(fromInclusive, toInclusive, pageRequest)
                : repository.findSucceededTopUpsAfter(
                        fromInclusive, toInclusive, after.completedAt(), after.id(), pageRequest);
        var views = entities.stream().map(PaymentsFacade::toView).toList();
        var nextCursor = views.isEmpty()
                ? null
                : new PaymentPageCursor(
                        views.get(views.size() - 1).occurredAt(), views.get(views.size() - 1).id());
        return new PaymentPage(views, nextCursor);
    }

    @Override
    public long countSucceededTopUps(Instant fromInclusive, Instant toInclusive) {
        validateRange(fromInclusive, toInclusive);
        return repository.countSucceededTopUps(fromInclusive, toInclusive);
    }

    private static void validateRange(Instant fromInclusive, Instant toInclusive) {
        if (fromInclusive == null || toInclusive == null) {
            throw new IllegalArgumentException("Range is required");
        }
        if (fromInclusive.isAfter(toInclusive)) {
            throw new IllegalArgumentException("Start must not be after end");
        }
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
