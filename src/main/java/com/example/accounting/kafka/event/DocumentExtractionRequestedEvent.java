package com.example.accounting.kafka.event;

import java.time.Instant;
import java.util.UUID;

public record DocumentExtractionRequestedEvent(
        UUID eventId,
        UUID documentId,
        UUID organizationId,
        UUID requestedByUserId,
        int eventVersion,
        Instant occurredAt
) {
    public static DocumentExtractionRequestedEvent create(UUID documentId, UUID organizationId, UUID requestedByUserId) {
        return new DocumentExtractionRequestedEvent(
                UUID.randomUUID(),
                documentId,
                organizationId,
                requestedByUserId,
                1,
                Instant.now()
        );
    }
}
