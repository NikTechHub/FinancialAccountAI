package com.example.accounting.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ExpenseSummaryResponse(
        LocalDate fromDate,
        LocalDate toDate,
        List<ExpenseAccountSummaryLine> byAccount,
        List<VendorExpenseSummaryLine> byVendor,
        BigDecimal totalAmount,
        long expenseCount
) {
}
