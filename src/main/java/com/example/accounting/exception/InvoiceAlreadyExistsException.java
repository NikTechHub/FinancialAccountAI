package com.example.accounting.exception;

public class InvoiceAlreadyExistsException extends RuntimeException {
    public InvoiceAlreadyExistsException(String invoiceNumber) {
        super("Invoice already exists with invoiceNumber: " + invoiceNumber);
    }
}
