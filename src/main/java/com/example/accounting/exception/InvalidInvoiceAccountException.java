package com.example.accounting.exception;

public class InvalidInvoiceAccountException extends RuntimeException {
    public InvalidInvoiceAccountException(String message) {
        super(message);
    }
}
