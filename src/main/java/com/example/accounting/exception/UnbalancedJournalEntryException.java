package com.example.accounting.exception;

import java.math.BigDecimal;

public class UnbalancedJournalEntryException extends RuntimeException {
    public UnbalancedJournalEntryException(BigDecimal debits, BigDecimal credits) {
        super("Unbalanced journal entry: total debits " + debits + " != total credits " + credits);
    }
}
