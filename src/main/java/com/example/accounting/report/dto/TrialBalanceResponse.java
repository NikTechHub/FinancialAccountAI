package com.example.accounting.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TrialBalanceResponse(
        LocalDate fromDate,
        LocalDate toDate,
        List<TrialBalanceLine> lines,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        BigDecimal totalDifference
) {
}
