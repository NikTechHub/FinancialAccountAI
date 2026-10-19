package com.example.accounting.report.dto;

import com.example.accounting.entity.AccountType;

import java.math.BigDecimal;
import java.util.UUID;

public record TrialBalanceLine(
        UUID accountId,
        String accountCode,
        String accountName,
        AccountType accountType,
        BigDecimal debitTotal,
        BigDecimal creditTotal,
        BigDecimal netBalance
) {
}
