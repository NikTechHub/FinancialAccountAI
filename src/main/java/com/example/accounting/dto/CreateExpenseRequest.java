package com.example.accounting.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateExpenseRequest(
        UUID vendorId,

        @NotNull(message = "expenseAccountId must not be null")
        UUID expenseAccountId,

        @NotNull(message = "paymentAccountId must not be null")
        UUID paymentAccountId,

        @NotNull(message = "amount must not be null")
        @DecimalMin(value = "0.01", message = "amount must be greater than 0")
        BigDecimal amount,

        @NotNull(message = "expenseDate must not be null")
        LocalDate expenseDate,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @Size(max = 100, message = "reference must be at most 100 characters")
        String reference
) {
}
