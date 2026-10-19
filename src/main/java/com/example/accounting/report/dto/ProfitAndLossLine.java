package com.example.accounting.report.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ProfitAndLossLine(
        UUID accountId,
        String accountCode,
        String accountName,
        BigDecimal amount
) {
}
