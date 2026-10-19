package com.example.accounting.exception;

public class AccountAlreadyExistsException extends RuntimeException {
    public AccountAlreadyExistsException(String code) {
        super("Account already exists with code: " + code);
    }
}
