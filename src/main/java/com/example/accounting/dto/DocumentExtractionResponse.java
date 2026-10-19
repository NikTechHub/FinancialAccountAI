package com.example.accounting.dto;

import com.example.accounting.entity.DocumentExtractionStatus;
import com.example.accounting.entity.DocumentType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DocumentExtractionResponse(
        UUID id,
        UUID documentId,
        DocumentType extractionType,
        DocumentExtractionStatus status,
        String model,
        String promptVersion,
        Object structuredData,
        BigDecimal confidence,
        Instant processingStartedAt,
        Instant processingCompletedAt,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
}
