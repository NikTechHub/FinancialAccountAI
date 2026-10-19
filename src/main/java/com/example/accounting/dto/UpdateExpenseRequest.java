package com.example.accounting.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record UpdateExpenseRequest(
        UUID vendorId,

        UUID expenseAccountId,

        UUID paymentAccountId,

        @DecimalMin(value = "0.01", message = "amount must be greater than 0")
        BigDecimal amount,

        LocalDate expenseDate,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @Size(max = 100, message = "reference must be at most 100 characters")
        String reference
) {
}
