package com.example.accounting.dto;

import com.example.accounting.entity.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateAccountRequest(
        @NotBlank(message = "code must not be blank")
        @Size(max = 20, message = "code must be at most 20 characters")
        String code,

        @NotBlank(message = "name must not be blank")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @NotNull(message = "type must not be null")
        AccountType type,

        UUID parentAccountId,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description
) {
}
