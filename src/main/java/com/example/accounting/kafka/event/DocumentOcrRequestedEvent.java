package com.example.accounting.kafka.event;

import java.time.Instant;
import java.util.UUID;

public record DocumentOcrRequestedEvent(
        UUID eventId,
        UUID documentId,
        UUID organizationId,
        UUID requestedByUserId,
        int eventVersion,
        Instant occurredAt
) {
    public static DocumentOcrRequestedEvent create(UUID documentId, UUID organizationId, UUID requestedByUserId) {
        return new DocumentOcrRequestedEvent(
                UUID.randomUUID(),
                documentId,
                organizationId,
                requestedByUserId,
                1,
                Instant.now()
        );
    }
}
