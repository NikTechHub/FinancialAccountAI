package com.example.accounting.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BalanceSheetResponse(
        LocalDate asOfDate,
        List<BalanceSheetLine> assetLines,
        List<BalanceSheetLine> liabilityLines,
        List<BalanceSheetLine> equityLines,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal totalEquity
) {
}
