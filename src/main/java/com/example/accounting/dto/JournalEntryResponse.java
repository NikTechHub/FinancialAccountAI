package com.example.accounting.dto;

import com.example.accounting.entity.JournalEntryStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record JournalEntryResponse(
        UUID id,
        UUID organizationId,
        String organizationName,
        String entryNumber,
        LocalDate entryDate,
        String description,
        String reference,
        JournalEntryStatus status,
        UUID createdBy,
        String createdByEmail,
        Instant createdAt,
        Instant updatedAt,
        List<JournalEntryLineResponse> lines,
        BigDecimal totalDebits,
        BigDecimal totalCredits
) {
}
