package com.example.accounting.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "firstName must not be blank")
        @Size(max = 100)
        String firstName,

        @NotBlank(message = "lastName must not be blank")
        @Size(max = 100)
        String lastName,

        @NotBlank(message = "email must not be blank")
        @Email(message = "email must be valid")
        @Size(max = 255)
        String email,

        @NotBlank(message = "password must not be blank")
        @Size(min = 8, max = 100)
        String password,

        // Organization fields — creation where appropriate
        @NotBlank(message = "organization name must not be blank")
        @Size(max = 255)
        String organizationName,

        @NotBlank(message = "legalName must not be blank")
        @Size(max = 255)
        String legalName,

        @NotBlank @Size(max = 100)
        String businessType,

        @NotBlank @Size(max = 100)
        String country,

        @NotBlank @Size(min = 3, max = 3)
        String currency,

        @NotBlank @Size(max = 64)
        String timezone
) {
}
