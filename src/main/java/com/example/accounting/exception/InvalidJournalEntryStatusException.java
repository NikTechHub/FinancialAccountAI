package com.example.accounting.exception;

public class InvalidJournalEntryStatusException extends RuntimeException {
    public InvalidJournalEntryStatusException(String message) {
        super(message);
    }
}
