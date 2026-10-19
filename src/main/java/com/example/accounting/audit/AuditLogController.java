package com.example.accounting.audit;

import com.example.accounting.dto.AuditLogResponse;
import com.example.accounting.security.AuthContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Authenticated read-only audit API. Audit history is restricted to
 * finance-responsible roles (OWNER, ADMIN, ACCOUNTANT); EMPLOYEE and VIEWER
 * receive 403. Results are always scoped to organizations the caller may
 * audit — there is no organization filter parameter.
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public ResponseEntity<Page<AuditLogResponse>> list(
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) AuditResourceType resourceType,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID userId = AuthContext.currentUserId();
        List<UUID> orgIds = auditLogService.readableOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            throw new AccessDeniedException("Audit history requires OWNER, ADMIN or ACCOUNTANT role");
        }
        // Default window: last 90 days, so unbounded queries stay cheap.
        LocalDate from = fromDate != null ? fromDate : LocalDate.now().minusDays(90);
        LocalDate to = toDate != null ? toDate : LocalDate.now();
        return ResponseEntity.ok(auditLogService.search(orgIds, action, resourceType, resourceId,
                actorUserId, success, from, to, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AuditLogResponse> getById(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        List<UUID> orgIds = auditLogService.readableOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            throw new AccessDeniedException("Audit history requires OWNER, ADMIN or ACCOUNTANT role");
        }
        return ResponseEntity.ok(auditLogService.getById(orgIds, id));
    }
}
