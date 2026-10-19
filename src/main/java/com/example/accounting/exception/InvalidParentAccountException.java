package com.example.accounting.exception;

public class InvalidParentAccountException extends RuntimeException {
    public InvalidParentAccountException(String message) {
        super(message);
    }
}
