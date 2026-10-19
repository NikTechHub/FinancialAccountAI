package com.example.accounting.exception;

import java.util.UUID;

public class RecordedExpenseModificationException extends RuntimeException {
    public RecordedExpenseModificationException(UUID id) {
        super("Recorded expense cannot be modified: " + id);
    }
    public RecordedExpenseModificationException(String message) {
        super(message);
    }
}
