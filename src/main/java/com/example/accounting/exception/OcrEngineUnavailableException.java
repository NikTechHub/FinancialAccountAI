package com.example.accounting.exception;

public class OcrEngineUnavailableException extends RuntimeException {
    public OcrEngineUnavailableException(String message) {
        super(message);
    }
}
