package com.example.accounting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record UpdateDocumentReviewRequest(
        @NotNull @Valid StructuredInvoiceExtraction reviewedData
) {
}
