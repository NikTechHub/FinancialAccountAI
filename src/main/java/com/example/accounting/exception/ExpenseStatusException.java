package com.example.accounting.exception;

public class ExpenseStatusException extends RuntimeException {
    public ExpenseStatusException(String message) {
        super(message);
    }
}
