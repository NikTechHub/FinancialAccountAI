package com.example.accounting.exception;

public class InvalidInvoiceCustomerException extends RuntimeException {
    public InvalidInvoiceCustomerException(String message) {
        super(message);
    }
}
