package com.example.accounting.dto;

import java.math.BigDecimal;
import java.util.List;

public record StructuredInvoiceExtraction(
        String documentType,
        String invoiceNumber,
        String invoiceDate,
        String dueDate,
        String currency,
        String vendorName,
        String vendorTaxIdentifier,
        String vendorEmail,
        String vendorPhone,
        String customerName,
        String customerTaxIdentifier,
        BigDecimal subtotal,
        BigDecimal taxAmount,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        String paymentTerms,
        String notes,
        List<StructuredLineItem> lineItems
) {
    public record StructuredLineItem(
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal taxRate,
            BigDecimal taxAmount,
            BigDecimal discountAmount,
            BigDecimal lineTotal
    ) {}
}
