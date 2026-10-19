package com.example.accounting.exception;

import java.util.UUID;

public class DocumentExtractionAlreadyProcessingException extends RuntimeException {
    public DocumentExtractionAlreadyProcessingException(UUID documentId) {
        super("Document extraction already in progress for document: " + documentId);
    }
}
