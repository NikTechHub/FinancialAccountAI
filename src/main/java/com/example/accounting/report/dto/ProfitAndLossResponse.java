package com.example.accounting.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ProfitAndLossResponse(
        LocalDate fromDate,
        LocalDate toDate,
        List<ProfitAndLossLine> revenueLines,
        List<ProfitAndLossLine> expenseLines,
        BigDecimal totalRevenue,
        BigDecimal totalExpenses,
        BigDecimal netProfit
) {
}
