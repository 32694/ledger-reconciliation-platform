package io.github.user32694.ledgerplatform.reconciliation;

public record StatementUpload(String fileName, byte[] content, String operator) {
    public StatementUpload {
        content = content == null ? null : content.clone();
    }

    @Override
    public byte[] content() {
        return content == null ? null : content.clone();
    }
}
