package com.example.accounting.report.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ExpenseAccountSummaryLine(
        UUID expenseAccountId,
        String expenseAccountCode,
        String expenseAccountName,
        BigDecimal totalAmount,
        long expenseCount
) {
}
