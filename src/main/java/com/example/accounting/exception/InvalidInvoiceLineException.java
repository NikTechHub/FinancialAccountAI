package com.example.accounting.exception;

public class InvalidInvoiceLineException extends RuntimeException {
    public InvalidInvoiceLineException(String message) {
        super(message);
    }
}
