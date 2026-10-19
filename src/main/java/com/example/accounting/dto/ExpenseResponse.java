package com.example.accounting.dto;

import com.example.accounting.entity.ExpenseStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ExpenseResponse(
        UUID id,
        UUID organizationId,
        String organizationName,
        UUID vendorId,
        String vendorName,
        UUID expenseAccountId,
        String expenseAccountCode,
        String expenseAccountName,
        UUID paymentAccountId,
        String paymentAccountCode,
        String paymentAccountName,
        BigDecimal amount,
        LocalDate expenseDate,
        String description,
        String reference,
        ExpenseStatus status,
        UUID journalEntryId,
        UUID createdBy,
        String createdByEmail,
        Instant createdAt,
        Instant updatedAt
) {
}
