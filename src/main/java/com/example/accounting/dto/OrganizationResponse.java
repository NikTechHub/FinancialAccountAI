package com.example.accounting.dto;

import java.time.Instant;
import java.util.UUID;

public record OrganizationResponse(
        UUID id,
        String name,
        String legalName,
        String businessType,
        String country,
        String currency,
        String timezone,
        Instant createdAt,
        Instant updatedAt
) {
}
