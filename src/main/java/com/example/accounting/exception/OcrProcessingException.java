package com.example.accounting.exception;

public class OcrProcessingException extends RuntimeException {
    public OcrProcessingException(String message) {
        super(message);
    }
    public OcrProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
