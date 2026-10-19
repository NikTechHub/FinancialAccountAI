package com.example.accounting.report.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record VendorExpenseSummaryLine(
        UUID vendorId,
        String vendorName,
        BigDecimal totalAmount,
        long expenseCount
) {
}
