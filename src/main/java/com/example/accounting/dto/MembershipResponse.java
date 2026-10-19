package com.example.accounting.dto;

import com.example.accounting.entity.MembershipRole;

import java.time.Instant;
import java.util.UUID;

public record MembershipResponse(
        UUID id,
        UUID userId,
        String userEmail,
        String userFirstName,
        String userLastName,
        UUID organizationId,
        String organizationName,
        MembershipRole role,
        Instant createdAt
) {
}
