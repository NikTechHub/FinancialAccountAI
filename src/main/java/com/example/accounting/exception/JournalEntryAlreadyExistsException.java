package com.example.accounting.exception;

public class JournalEntryAlreadyExistsException extends RuntimeException {
    public JournalEntryAlreadyExistsException(String entryNumber) {
        super("Journal entry already exists with entryNumber: " + entryNumber);
    }
}
