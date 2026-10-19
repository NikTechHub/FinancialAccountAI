package com.example.accounting.exception;

public class InvoiceStatusException extends RuntimeException {
    public InvoiceStatusException(String message) {
        super(message);
    }
}
