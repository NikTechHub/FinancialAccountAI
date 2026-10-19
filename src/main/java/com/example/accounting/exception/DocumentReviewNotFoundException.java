package com.example.accounting.exception;

import java.util.UUID;

public class DocumentReviewNotFoundException extends RuntimeException {
    public DocumentReviewNotFoundException(UUID documentId) {
        super("Document review not found for document: " + documentId);
    }
}
