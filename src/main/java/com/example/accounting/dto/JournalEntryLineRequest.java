package com.example.accounting.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record JournalEntryLineRequest(
        @NotNull(message = "accountId must not be null")
        UUID accountId,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        BigDecimal debitAmount,

        BigDecimal creditAmount
) {
}
