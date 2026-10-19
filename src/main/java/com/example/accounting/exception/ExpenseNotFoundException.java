package com.example.accounting.exception;

import java.util.UUID;

public class ExpenseNotFoundException extends RuntimeException {
    public ExpenseNotFoundException(UUID id) {
        super("Expense not found with id: " + id);
    }
}
