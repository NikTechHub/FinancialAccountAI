package com.example.accounting.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateVendorRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Email(message = "email must be valid")
        @Size(max = 255, message = "email must be at most 255 characters")
        String email,

        @Size(max = 50, message = "phone must be at most 50 characters")
        String phone,

        @Size(max = 500, message = "address must be at most 500 characters")
        String address,

        @Size(max = 100, message = "taxIdentifier must be at most 100 characters")
        String taxIdentifier,

        @Size(max = 100, message = "paymentTerms must be at most 100 characters")
        String paymentTerms
) {
}
