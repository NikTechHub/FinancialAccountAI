package com.example.accounting.exception;

public class OrganizationAlreadyExistsException extends RuntimeException {

    public OrganizationAlreadyExistsException(String name) {
        super("Organization already exists with name: " + name);
    }
}
