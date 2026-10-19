package com.example.accounting.dto;

import com.example.accounting.entity.MembershipRole;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateMembershipRequest(
        @NotNull(message = "userId must not be null")
        UUID userId,

        @NotNull(message = "role must not be null")
        MembershipRole role
) {
}
