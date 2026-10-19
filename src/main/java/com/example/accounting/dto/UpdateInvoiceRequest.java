package com.example.accounting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record UpdateInvoiceRequest(
        UUID customerId,

        @Size(max = 50, message = "invoiceNumber must be at most 50 characters")
        String invoiceNumber,

        LocalDate invoiceDate,

        LocalDate dueDate,

        @Size(max = 1000, message = "notes must be at most 1000 characters")
        String notes,

        @Size(max = 1000, message = "terms must be at most 1000 characters")
        String terms,

        @Valid
        List<InvoiceLineRequest> lines
) {
}
