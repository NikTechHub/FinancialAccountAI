package com.example.accounting.audit;

import java.util.UUID;

/**
 * Parameters for one audit record. JSON payloads must be produced via
 * {@link AuditLogService#toJson(Object)} (never string concatenation).
 * {@code organizationId} is null only for unattributable pre-authentication
 * security events; {@code actorUserId} is null for system/worker events.
 */
public record AuditCommand(
        AuditAction action,
        AuditResourceType resourceType,
        UUID organizationId,
        UUID actorUserId,
        UUID resourceId,
        String description,
        String beforeJson,
        String afterJson,
        String metadataJson,
        boolean success
) {
}
