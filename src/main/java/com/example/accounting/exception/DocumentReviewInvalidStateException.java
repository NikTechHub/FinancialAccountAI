package com.example.accounting.exception;

public class DocumentReviewInvalidStateException extends RuntimeException {
    public DocumentReviewInvalidStateException(String message) {
        super(message);
    }
}
