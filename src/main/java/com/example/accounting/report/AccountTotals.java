package com.example.accounting.report;

import com.example.accounting.entity.AccountType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Internal JPQL projection for per-account debit/credit aggregation.
 * Net balances are derived in {@code ReportService} using normal-balance rules.
 */
public record AccountTotals(
        UUID accountId,
        String accountCode,
        String accountName,
        AccountType accountType,
        BigDecimal debitTotal,
        BigDecimal creditTotal
) {
}
