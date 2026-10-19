package com.example.accounting.exception;

public class CustomerAlreadyExistsException extends RuntimeException {
    public CustomerAlreadyExistsException(String name) {
        super("Customer already exists with name: " + name);
    }
}
