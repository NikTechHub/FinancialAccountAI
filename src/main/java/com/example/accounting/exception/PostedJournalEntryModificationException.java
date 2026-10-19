package com.example.accounting.exception;

import java.util.UUID;

public class PostedJournalEntryModificationException extends RuntimeException {
    public PostedJournalEntryModificationException(UUID id) {
        super("Posted journal entry cannot be modified: " + id);
    }
    public PostedJournalEntryModificationException(String message) {
        super(message);
    }
}
