package com.example.accounting.dto;

import java.time.Instant;
import java.util.UUID;

public record VendorResponse(
        UUID id,
        UUID organizationId,
        String organizationName,
        String name,
        String email,
        String phone,
        String address,
        String taxIdentifier,
        String paymentTerms,
        Boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
