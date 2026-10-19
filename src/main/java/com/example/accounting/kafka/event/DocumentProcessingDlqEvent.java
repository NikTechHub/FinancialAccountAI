package com.example.accounting.kafka.event;

import java.time.Instant;
import java.util.UUID;

public record DocumentProcessingDlqEvent(
        UUID originalEventId,
        UUID documentId,
        UUID organizationId,
        String processingType,
        String failureReason,
        Instant occurredAt
) {
    public static DocumentProcessingDlqEvent fromOcr(DocumentOcrRequestedEvent event, String reason) {
        return new DocumentProcessingDlqEvent(
                event.eventId(),
                event.documentId(),
                event.organizationId(),
                "OCR",
                reason != null ? reason.substring(0, Math.min(reason.length(), 1000)) : "Unknown",
                Instant.now()
        );
    }

    public static DocumentProcessingDlqEvent fromExtraction(DocumentExtractionRequestedEvent event, String reason) {
        return new DocumentProcessingDlqEvent(
                event.eventId(),
                event.documentId(),
                event.organizationId(),
                "EXTRACTION",
                reason != null ? reason.substring(0, Math.min(reason.length(), 1000)) : "Unknown",
                Instant.now()
        );
    }
}
