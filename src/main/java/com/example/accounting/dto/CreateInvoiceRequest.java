package com.example.accounting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateInvoiceRequest(
        @NotNull(message = "customerId must not be null")
        UUID customerId,

        @NotBlank(message = "invoiceNumber must not be blank")
        @Size(max = 50, message = "invoiceNumber must be at most 50 characters")
        String invoiceNumber,

        @NotNull(message = "invoiceDate must not be null")
        LocalDate invoiceDate,

        LocalDate dueDate,

        @Size(max = 1000, message = "notes must be at most 1000 characters")
        String notes,

        @Size(max = 1000, message = "terms must be at most 1000 characters")
        String terms,

        @NotEmpty(message = "invoice must have at least one line")
        @Valid
        List<InvoiceLineRequest> lines
) {
}
