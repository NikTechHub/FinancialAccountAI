package com.example.accounting.exception;

public class InvoiceAccountingException extends RuntimeException {
    public InvoiceAccountingException(String message) {
        super(message);
    }
    public InvoiceAccountingException(String message, Throwable cause) {
        super(message, cause);
    }
}
