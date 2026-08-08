package io.github.user32694.ledgerplatform.payments.internal;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.payments.IdempotencyConflictException;
import io.github.user32694.ledgerplatform.payments.TopUpCommand;
import io.github.user32694.ledgerplatform.payments.TransferCommand;
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
        return submit(
                "TOP_UP",
                command.idempotencyKey(),
                null,
                command.payeeAccountId(),
                command.amountCents(),
                fingerprint("TOP_UP", null, command.payeeAccountId(), command.amountCents()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    UUID submit(TransferCommand command) {
        validate(command);
        return submit(
                "TRANSFER",
                command.idempotencyKey(),
                command.payerAccountId(),
                command.payeeAccountId(),
                command.amountCents(),
                fingerprint(
                        "TRANSFER",
                        command.payerAccountId(),
                        command.payeeAccountId(),
                        command.amountCents()));
    }

    private UUID submit(
            String paymentType,
            String idempotencyKey,
            UUID payerAccountId,
            UUID payeeAccountId,
            long amountCents,
            String fingerprint) {
        repository.acquireIdempotencyLock(idempotencyKey);
        var existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return resolve(existing.orElseThrow(), fingerprint);
        }

        if (payerAccountId != null) {
            try {
                accountsApi.get(payerAccountId);
            } catch (IllegalArgumentException exception) {
                if ("TRANSFER".equals(paymentType)) {
                    throw new IllegalArgumentException(
                            "Payer account does not exist: " + payerAccountId, exception);
                }
                throw exception;
            }
        }
        try {
            accountsApi.get(payeeAccountId);
        } catch (IllegalArgumentException exception) {
            if ("TRANSFER".equals(paymentType)) {
                throw new IllegalArgumentException(
                        "Payee account does not exist: " + payeeAccountId, exception);
            }
            throw exception;
        }
        UUID paymentId = UUID.randomUUID();
        repository.insertPending(
                paymentId,
                idempotencyKey,
                fingerprint,
                paymentType.replace("_", "") + "-"
                        + UUID.randomUUID().toString().replace("-", ""),
                paymentType,
                payerAccountId,
                payeeAccountId,
                amountCents,
                null,
                null,
                Instant.now());

        var payment = repository.findByIdempotencyKey(idempotencyKey)
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

    private static void validate(TransferCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Transfer command is required");
        }
        validateIdempotencyKey(command.idempotencyKey());
        if (command.payerAccountId() == null) {
            throw new IllegalArgumentException("Payer account id is required");
        }
        if (command.payeeAccountId() == null) {
            throw new IllegalArgumentException("Payee account id is required");
        }
        if (command.payerAccountId().equals(command.payeeAccountId())) {
            throw new IllegalArgumentException("Payer and payee accounts must be different");
        }
        if (command.amountCents() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }

    private static void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
        if (idempotencyKey.codePointCount(0, idempotencyKey.length()) > 128) {
            throw new IllegalArgumentException("Idempotency key must not exceed 128 characters");
        }
        if (idempotencyKey.codePoints().anyMatch(
                codePoint -> codePoint == 0 || Character.isISOControl(codePoint))) {
            throw new IllegalArgumentException("Idempotency key must not contain control characters");
        }
    }

    private static String fingerprint(
            String paymentType, UUID payerAccountId, UUID payeeAccountId, long amountCents) {
        String canonical = paymentType + "|" + payerAccountId + "|" + payeeAccountId + "|"
                + amountCents + "|CNY";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
