package com.example.accounting.exception;

import java.util.UUID;

public class DocumentReviewAlreadyExistsException extends RuntimeException {
    public DocumentReviewAlreadyExistsException(UUID documentId) {
        super("Document review already exists for document: " + documentId);
    }
}
