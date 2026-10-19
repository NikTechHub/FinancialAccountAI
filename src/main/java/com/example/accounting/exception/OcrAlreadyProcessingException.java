package com.example.accounting.exception;

import java.util.UUID;

public class OcrAlreadyProcessingException extends RuntimeException {
    public OcrAlreadyProcessingException(UUID documentId) {
        super("OCR already in progress for document: " + documentId);
    }
    public OcrAlreadyProcessingException(String message) {
        super(message);
    }
}
