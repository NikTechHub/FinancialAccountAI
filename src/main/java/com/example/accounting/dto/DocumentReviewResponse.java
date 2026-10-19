package com.example.accounting.dto;

import com.example.accounting.entity.DocumentReviewStatus;

import java.time.Instant;
import java.util.UUID;

public record DocumentReviewResponse(
        UUID id,
        UUID documentId,
        UUID extractionId,
        DocumentReviewStatus status,
        Object originalAiData,
        Object reviewedData,
        ReviewerInfo reviewedBy,
        Instant reviewedAt,
        String reviewComment,
        Instant createdAt,
        Instant updatedAt
) {
    public record ReviewerInfo(
            UUID id,
            String firstName,
            String lastName,
            String email
    ) {}
}
