package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ReconciliationBatchView;
import io.github.user32694.ledgerplatform.reconciliation.StatementUpload;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
class ReconciliationImportService {
    private final StatementCsvParser parser;
    private final ReconciliationStore store;

    ReconciliationImportService(StatementCsvParser parser, ReconciliationStore store) {
        this.parser = parser;
        this.store = store;
    }

    ReconciliationBatchView importStatement(StatementUpload upload) {
        validateUpload(upload);
        byte[] content = upload.content();
        String hash = sha256(content);
        var existing = store.findByHash(hash);
        if (existing.isPresent()) {
            return existing.get().toView();
        }
        try {
            ParsedStatement parsed = parser.parse(content);
            return store.persistImported(
                            upload.fileName(), hash, parsed, upload.operator(), Instant.now())
                    .toView();
        } catch (RuntimeException exception) {
            var raced = store.findByHash(hash);
            if (raced.isPresent()) {
                return raced.get().toView();
            }
            String message = stableMessage(exception);
            try {
                return store.persistImportFailure(
                                upload.fileName(), hash, message, upload.operator(), Instant.now())
                        .toView();
            } catch (DataIntegrityViolationException race) {
                return store.findByHash(hash)
                        .orElseThrow(() -> race)
                        .toView();
            }
        }
    }

    private static void validateUpload(StatementUpload upload) {
        if (upload == null) {
            throw new IllegalArgumentException("Upload is required");
        }
        if (upload.fileName() == null || upload.fileName().isBlank()
                || upload.fileName().codePointCount(0, upload.fileName().length()) > 255) {
            throw new IllegalArgumentException("File name is invalid");
        }
        if (upload.content() == null || upload.content().length == 0) {
            throw new IllegalArgumentException("File content is empty");
        }
        if (upload.operator() == null || upload.operator().isBlank()
                || upload.operator().codePointCount(0, upload.operator().length()) > 128) {
            throw new IllegalArgumentException("Operator is invalid");
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String stableMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.substring(0, Math.min(message.length(), 2000));
    }
}
