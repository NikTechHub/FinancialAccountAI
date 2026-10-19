package com.example.accounting.dto;

import java.util.UUID;

public record ProcessingAcceptedResponse(
        UUID documentId,
        UUID operationId,
        String status,
        String message
) {
}
