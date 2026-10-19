package com.example.accounting.dto;

import com.example.accounting.entity.InvoiceStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        UUID organizationId,
        String organizationName,
        UUID customerId,
        String customerName,
        String invoiceNumber,
        LocalDate invoiceDate,
        LocalDate dueDate,
        InvoiceStatus status,
        BigDecimal subtotal,
        BigDecimal taxAmount,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        BigDecimal amountPaid,
        BigDecimal balanceDue,
        String notes,
        String terms,
        UUID journalEntryId,
        UUID createdBy,
        String createdByEmail,
        Instant createdAt,
        Instant updatedAt,
        List<InvoiceLineResponse> lines
) {
}
