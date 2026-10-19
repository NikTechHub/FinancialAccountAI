package com.example.accounting.exception;

public class OcrUnsupportedDocumentException extends RuntimeException {
    public OcrUnsupportedDocumentException(String message) {
        super(message);
    }
}
