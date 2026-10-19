package com.example.accounting.audit;

import java.util.UUID;

public class AuditLogNotFoundException extends RuntimeException {
    public AuditLogNotFoundException(UUID id) {
        super("Audit log not found with id: " + id);
    }
}
