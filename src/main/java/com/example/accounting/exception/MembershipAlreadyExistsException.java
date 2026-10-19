package com.example.accounting.exception;

import java.util.UUID;

public class MembershipAlreadyExistsException extends RuntimeException {

    public MembershipAlreadyExistsException(UUID userId, UUID organizationId) {
        super("User " + userId + " already has membership in organization " + organizationId);
    }
}
