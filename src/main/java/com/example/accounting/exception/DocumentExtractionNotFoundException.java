package com.example.accounting.exception;

import java.util.UUID;

public class DocumentExtractionNotFoundException extends RuntimeException {
    public DocumentExtractionNotFoundException(UUID documentId) {
        super("Document extraction not found for document: " + documentId);
    }
}
