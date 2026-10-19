package com.example.accounting.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InvoiceSummaryResponse(
        LocalDate fromDate,
        LocalDate toDate,
        long invoiceCount,
        BigDecimal totalInvoiced,
        BigDecimal totalPaid,
        BigDecimal totalOutstanding
) {
}
