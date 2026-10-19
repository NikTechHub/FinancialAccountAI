package com.example.accounting.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DashboardResponse(
        LocalDate fromDate,
        LocalDate toDate,
        BigDecimal totalRevenue,
        BigDecimal totalExpenses,
        BigDecimal netProfit,
        BigDecimal totalInvoiced,
        BigDecimal totalOutstanding,
        long customerCount,
        long vendorCount,
        long invoiceCount,
        long expenseCount
) {
}
