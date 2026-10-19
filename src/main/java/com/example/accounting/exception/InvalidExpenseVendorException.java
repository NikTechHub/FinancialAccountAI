package com.example.accounting.exception;

public class InvalidExpenseVendorException extends RuntimeException {
    public InvalidExpenseVendorException(String message) {
        super(message);
    }
}
