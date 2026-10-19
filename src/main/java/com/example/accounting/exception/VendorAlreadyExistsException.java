package com.example.accounting.exception;

public class VendorAlreadyExistsException extends RuntimeException {
    public VendorAlreadyExistsException(String name) {
        super("Vendor already exists with name: " + name);
    }
}
