package com.example.accounting.report;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Internal JPQL projection for per-customer invoice exposure.
 */
public record CustomerOutstanding(
        UUID customerId,
        String customerName,
        long invoiceCount,
        BigDecimal totalInvoiced,
        BigDecimal totalOutstanding
) {
}
