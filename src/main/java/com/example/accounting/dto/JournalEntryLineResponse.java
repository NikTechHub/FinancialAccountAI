package com.example.accounting.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record JournalEntryLineResponse(
        UUID id,
        UUID accountId,
        String accountCode,
        String accountName,
        String description,
        BigDecimal debitAmount,
        BigDecimal creditAmount
) {
}
