package com.example.accounting.exception;

import java.util.UUID;

public class InvalidAccountForJournalEntryException extends RuntimeException {
    public InvalidAccountForJournalEntryException(String message) {
        super(message);
    }
    public InvalidAccountForJournalEntryException(UUID accountId) {
        super("Invalid account for journal entry: " + accountId);
    }
}
