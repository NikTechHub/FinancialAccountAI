package com.example.accounting.exception;

public class SystemAccountModificationException extends RuntimeException {
    public SystemAccountModificationException(String message) {
        super(message);
    }
}
