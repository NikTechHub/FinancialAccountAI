package com.example.accounting.exception;

import java.util.UUID;

public class JournalEntryNotFoundException extends RuntimeException {
    public JournalEntryNotFoundException(UUID id) {
        super("Journal entry not found with id: " + id);
    }
}
