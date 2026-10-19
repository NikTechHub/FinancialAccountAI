package com.example.accounting.exception;

import java.util.UUID;

public class DocumentReviewAlreadyApprovedException extends RuntimeException {
    public DocumentReviewAlreadyApprovedException(UUID documentId) {
        super("Document review already approved for document: " + documentId);
    }
}
