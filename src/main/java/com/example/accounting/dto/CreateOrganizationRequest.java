package com.example.accounting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateOrganizationRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @NotBlank(message = "legalName must not be blank")
        @Size(max = 255, message = "legalName must be at most 255 characters")
        String legalName,

        @NotBlank(message = "businessType must not be blank")
        @Size(max = 100, message = "businessType must be at most 100 characters")
        String businessType,

        @NotBlank(message = "country must not be blank")
        @Size(max = 100, message = "country must be at most 100 characters")
        String country,

        @NotBlank(message = "currency must not be blank")
        @Size(min = 3, max = 3, message = "currency must be exactly 3 characters")
        String currency,

        @NotBlank(message = "timezone must not be blank")
        @Size(max = 64, message = "timezone must be at most 64 characters")
        String timezone
) {
}
