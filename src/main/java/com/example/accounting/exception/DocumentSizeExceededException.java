package com.example.accounting.exception;

public class DocumentSizeExceededException extends RuntimeException {
    public DocumentSizeExceededException(String message) {
        super(message);
    }
}
