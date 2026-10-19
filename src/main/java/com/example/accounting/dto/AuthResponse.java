package com.example.accounting.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType, // Bearer
        long expiresIn,
        UserResponse user,
        OrganizationResponse organization
) {
}
