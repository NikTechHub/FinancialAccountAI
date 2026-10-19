package com.example.accounting.exception;

import java.util.UUID;

public class OcrResultNotFoundException extends RuntimeException {
    public OcrResultNotFoundException(UUID documentId) {
        super("OCR result not found for document: " + documentId);
    }
}
