package com.example.accounting.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record InvoiceLineResponse(
        UUID id,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal taxRate,
        BigDecimal discountAmount,
        UUID revenueAccountId,
        String revenueAccountCode,
        String revenueAccountName,
        BigDecimal lineSubtotal,
        BigDecimal lineTaxAmount,
        BigDecimal lineTotal
) {
}
