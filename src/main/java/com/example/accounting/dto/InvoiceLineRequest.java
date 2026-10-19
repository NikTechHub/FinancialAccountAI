package com.example.accounting.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record InvoiceLineRequest(
        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @NotNull(message = "quantity must not be null")
        @DecimalMin(value = "0.0001", message = "quantity must be greater than 0")
        BigDecimal quantity,

        @NotNull(message = "unitPrice must not be null")
        @DecimalMin(value = "0.00", message = "unitPrice must not be negative")
        BigDecimal unitPrice,

        @NotNull(message = "taxRate must not be null")
        BigDecimal taxRate,

        @NotNull(message = "discountAmount must not be null")
        BigDecimal discountAmount,

        @NotNull(message = "revenueAccountId must not be null")
        UUID revenueAccountId
) {
}
