package com.example.accounting.exception;

public class AiExtractionValidationException extends RuntimeException {
    public AiExtractionValidationException(String message) {
        super(message);
    }
    public AiExtractionValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
