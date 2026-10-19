package com.example.accounting.exception;

public class InvalidExpenseAccountException extends RuntimeException {
    public InvalidExpenseAccountException(String message) {
        super(message);
    }
}
