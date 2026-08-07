package io.github.user32694.ledgerplatform.ledger;

public class DuplicateBusinessReferenceException extends RuntimeException {
    public DuplicateBusinessReferenceException(String businessReference) {
        super("Business reference already exists: " + businessReference);
    }

    public DuplicateBusinessReferenceException(String businessReference, Throwable cause) {
        super("Business reference already exists: " + businessReference, cause);
    }
}
