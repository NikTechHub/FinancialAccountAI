package com.example.accounting.dto;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String firstName,
        String lastName,
        String email,
        Boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
