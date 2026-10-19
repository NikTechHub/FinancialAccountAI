package com.example.accounting.dto;

import com.example.accounting.entity.AccountType;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        UUID organizationId,
        String organizationName,
        String code,
        String name,
        AccountType type,
        UUID parentAccountId,
        String parentAccountName,
        String description,
        Boolean active,
        Boolean systemAccount,
        Instant createdAt,
        Instant updatedAt
) {
}
