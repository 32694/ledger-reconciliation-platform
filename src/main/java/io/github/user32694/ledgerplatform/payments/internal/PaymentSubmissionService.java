package io.github.user32694.ledgerplatform.payments.internal;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.payments.IdempotencyConflictException;
import io.github.user32694.ledgerplatform.payments.TopUpCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentSubmissionService {
    private final PaymentInstructionRepository repository;
    private final AccountsApi accountsApi;

    PaymentSubmissionService(PaymentInstructionRepository repository, AccountsApi accountsApi) {
        this.repository = repository;
        this.accountsApi = accountsApi;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    UUID submit(TopUpCommand command) {
        validate(command);
        String fingerprint = fingerprint(command);
        repository.acquireIdempotencyLock(command.idempotencyKey());
        var existing = repository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return resolve(existing.orElseThrow(), fingerprint);
        }

        accountsApi.get(command.payeeAccountId());
        UUID paymentId = UUID.randomUUID();
        repository.insertPending(
                paymentId,
                command.idempotencyKey(),
                fingerprint,
                "TOPUP-" + UUID.randomUUID().toString().replace("-", ""),
                command.payeeAccountId(),
                command.amountCents(),
                Instant.now());

        var payment = repository.findByIdempotencyKey(command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Payment instruction was not created"));
        return resolve(payment, fingerprint);
    }

    private static UUID resolve(PaymentInstructionEntity payment, String fingerprint) {
        if (!fingerprint.equals(payment.requestFingerprint())) {
            throw new IdempotencyConflictException();
        }
        return payment.id();
    }

    private static void validate(TopUpCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Top-up command is required");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
        if (command.idempotencyKey().codePointCount(0, command.idempotencyKey().length()) > 128) {
            throw new IllegalArgumentException("Idempotency key must not exceed 128 characters");
        }
        if (command.idempotencyKey().codePoints().anyMatch(
                codePoint -> codePoint == 0 || Character.isISOControl(codePoint))) {
            throw new IllegalArgumentException("Idempotency key must not contain control characters");
        }
        if (command.payeeAccountId() == null) {
            throw new IllegalArgumentException("Payee account id is required");
        }
        if (command.amountCents() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }

    private static String fingerprint(TopUpCommand command) {
        String canonical = "TOP_UP|" + command.payeeAccountId() + "|"
                + command.amountCents() + "|CNY";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
