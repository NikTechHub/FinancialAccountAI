package com.example.accounting.report.dto;

import com.example.accounting.entity.AccountType;

import java.math.BigDecimal;
import java.util.UUID;

public record BalanceSheetLine(
        UUID accountId,
        String accountCode,
        String accountName,
        AccountType accountType,
        BigDecimal balance
) {
}
