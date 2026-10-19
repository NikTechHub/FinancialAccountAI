package com.example.accounting.exception;

import java.util.UUID;

public class OcrNotCompletedException extends RuntimeException {
    public OcrNotCompletedException(UUID documentId) {
        super("OCR not completed for document: " + documentId + ". Please run OCR first.");
    }
}
