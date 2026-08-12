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
    private final ReconciliationRuleService ruleService;

    ReconciliationImportService(
            StatementCsvParser parser,
            ReconciliationStore store,
            ReconciliationRuleService ruleService) {
        this.parser = parser;
        this.store = store;
        this.ruleService = ruleService;
    }

    ReconciliationBatchView importStatement(StatementUpload upload) {
        /*
         * 导入先校验并解析规则，再用文件 SHA-256 做幂等去重。
         * 同一文件重复上传直接返回已有批次；并发上传发生唯一键竞争时也读取胜出的批次。
         */
        validateUpload(upload);
        var resolvedRule = ruleService.resolveImportRule(upload.channelCode());
        byte[] content = upload.content();
        String hash = sha256(content);
        var existing = store.findByHash(hash);
        if (existing.isPresent()) {
            return existing.get().toView();
        }
        try {
            // 解析失败会保留 IMPORT_FAILED 批次，便于运营人员看到失败原因并重试。
            ParsedStatement parsed = parser.parse(content);
            return store.persistImported(
                            upload.fileName(),
                            hash,
                            resolvedRule.channelId(),
                            resolvedRule.ruleVersionId(),
                            parsed,
                            upload.operator(),
                            Instant.now())
                    .toView();
        } catch (RuntimeException exception) {
            var raced = store.findByHash(hash);
            if (raced.isPresent()) {
                return raced.get().toView();
            }
            String message = stableMessage(exception);
            try {
                return store.persistImportFailure(
                                upload.fileName(),
                                hash,
                                resolvedRule.channelId(),
                                resolvedRule.ruleVersionId(),
                                message,
                                upload.operator(),
                                Instant.now())
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
        if (upload.channelCode() == null || upload.channelCode().isBlank()
                || upload.channelCode().codePointCount(0, upload.channelCode().length()) > 32) {
            throw new IllegalArgumentException("Channel code is invalid");
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
