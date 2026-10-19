package com.example.accounting.exception;

import java.util.UUID;

public class OrganizationNotFoundException extends RuntimeException {

    public OrganizationNotFoundException(UUID id) {
        super("Organization not found with id: " + id);
    }
}
