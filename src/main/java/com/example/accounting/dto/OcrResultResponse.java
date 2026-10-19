package com.example.accounting.dto;

import com.example.accounting.entity.OcrStatus;

import java.time.Instant;
import java.util.UUID;

public record OcrResultResponse(
        UUID id,
        UUID documentId,
        OcrStatus status,
        String extractedText,
        Integer pageCount,
        Instant processingStartedAt,
        Instant processingCompletedAt,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
}
