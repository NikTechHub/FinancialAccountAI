package com.example.accounting.dto;

import com.example.accounting.audit.AuditAction;
import com.example.accounting.audit.AuditResourceType;

import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        UUID organizationId,
        AuditAction action,
        AuditResourceType resourceType,
        UUID resourceId,
        UUID actorUserId,
        String actorName,
        String description,
        Object beforeData,
        Object afterData,
        Object metadata,
        String ipAddress,
        String userAgent,
        Boolean success,
        Instant createdAt
) {
}
