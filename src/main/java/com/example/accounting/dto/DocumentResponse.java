package com.example.accounting.dto;

import com.example.accounting.entity.DocumentStatus;
import com.example.accounting.entity.DocumentType;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        UUID organizationId,
        UUID invoiceId,
        UUID expenseId,
        String originalFileName,
        String contentType,
        Long fileSize,
        DocumentType documentType,
        DocumentStatus status,
        String checksum,
        UUID uploadedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
