package com.example.accounting.dto;

import com.example.accounting.entity.AccountType;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateAccountRequest(
        @Size(max = 20, message = "code must be at most 20 characters")
        String code,

        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        AccountType type,

        UUID parentAccountId,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        Boolean active
) {
}
