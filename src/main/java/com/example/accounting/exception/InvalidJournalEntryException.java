package com.example.accounting.exception;

public class InvalidJournalEntryException extends RuntimeException {
    public InvalidJournalEntryException(String message) {
        super(message);
    }
}
