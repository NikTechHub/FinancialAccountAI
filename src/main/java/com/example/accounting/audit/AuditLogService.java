package com.example.accounting.audit;

import com.example.accounting.dto.AuditLogResponse;
import com.example.accounting.entity.MembershipRole;
import com.example.accounting.entity.Organization;
import com.example.accounting.entity.User;
import com.example.accounting.repository.OrganizationMembershipRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Central audit API. Business services call {@link #record} at the end of a
 * successful mutation so the audit row commits (or rolls back) together with
 * the business change. {@link #recordInNewTransaction} is reserved for cases
 * where the audit must survive the surrounding outcome: authentication
 * failures (the login transaction rolls back by definition), durable worker
 * failure records, and read-path events (report views, assistant queries)
 * originating from read-only transactions.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    public AuditLogService(AuditLogRepository auditLogRepository,
                           OrganizationMembershipRepository membershipRepository,
                           ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.membershipRepository = membershipRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Organizations whose audit history the user may read: only those where
     * their role is OWNER, ADMIN or ACCOUNTANT.
     */
    @Transactional(readOnly = true)
    public List<UUID> readableOrganizationIds(UUID userId) {
        return membershipRepository.findByUserId(userId).stream()
                .filter(m -> m.getRole() != null && canReadAudit(m.getRole()))
                .map(m -> m.getOrganization().getId())
                .collect(java.util.stream.Collectors.toList());
    }

    @Transactional
    public AuditLogResponse record(AuditCommand command) {
        return toResponse(persist(command));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLogResponse recordInNewTransaction(AuditCommand command) {
        return toResponse(persist(command));
    }

    private AuditLog persist(AuditCommand command) {
        if (command.action() == null || command.resourceType() == null) {
            throw new IllegalArgumentException("Audit action and resource type are required");
        }
        Organization organization = command.organizationId() == null ? null
                : entityRef(Organization.class, command.organizationId());
        User actor = command.actorUserId() == null ? null
                : entityRef(User.class, command.actorUserId());
        AuditLog auditLog = new AuditLog(
                organization, actor, command.action(), command.resourceType(), command.resourceId(),
                truncate(command.description(), 1000),
                command.beforeJson(), command.afterJson(), command.metadataJson(),
                RequestAuditContext.ipAddress(), RequestAuditContext.userAgent(), command.success());
        return auditLogRepository.save(auditLog);
    }

    private <T> T entityRef(Class<T> type, UUID id) {
        return entityManager.getReference(type, id);
    }

    /**
     * Centralized JSON serialization for audit payloads. Never throws:
     * unserializable values yield null rather than breaking the business
     * operation (callers still pass only safe snapshots).
     */
    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize audit payload; storing null");
            return null;
        }
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> search(List<UUID> readableOrgIds,
                                          AuditAction action,
                                          AuditResourceType resourceType,
                                          UUID resourceId,
                                          UUID actorUserId,
                                          Boolean success,
                                          LocalDate fromDate,
                                          LocalDate toDate,
                                          Pageable pageable) {
        if (readableOrgIds == null || readableOrgIds.isEmpty()) {
            return Page.empty(pageable);
        }
        if (fromDate == null || toDate == null) {
            throw new IllegalArgumentException("fromDate and toDate are required");
        }
        if (fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("fromDate must be on or before toDate");
        }
        // UTC day bounds, end-exclusive.
        Instant from = fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        // Fixed deterministic ordering; client sort is intentionally ignored.
        Pageable ordered = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        return auditLogRepository.search(readableOrgIds, action, resourceType, resourceId,
                actorUserId, success, from, to, ordered).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public AuditLogResponse getById(List<UUID> readableOrgIds, UUID id) {
        AuditLog auditLog = auditLogRepository.findById(id)
                .orElseThrow(() -> new AuditLogNotFoundException(id));
        if (auditLog.getOrganization() == null
                || !readableOrgIds.contains(auditLog.getOrganization().getId())) {
            // Same safe-empty convention as other tenant-scoped reads.
            throw new AuditLogNotFoundException(id);
        }
        return toResponse(auditLog);
    }

    private AuditLogResponse toResponse(AuditLog auditLog) {
        UUID organizationId = auditLog.getOrganization() == null
                ? null : auditLog.getOrganization().getId();
        UUID actorUserId = auditLog.getActorUser() == null
                ? null : auditLog.getActorUser().getId();
        String actorName = null;
        if (auditLog.getActorUser() != null) {
            String first = auditLog.getActorUser().getFirstName();
            String last = auditLog.getActorUser().getLastName();
            actorName = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
            if (actorName.isEmpty()) {
                actorName = null;
            }
        }
        return new AuditLogResponse(
                auditLog.getId(),
                organizationId,
                auditLog.getAction(),
                auditLog.getResourceType(),
                auditLog.getResourceId(),
                actorUserId,
                actorName,
                auditLog.getDescription(),
                parseJson(auditLog.getBeforeData()),
                parseJson(auditLog.getAfterData()),
                parseJson(auditLog.getMetadata()),
                auditLog.getIpAddress(),
                auditLog.getUserAgent(),
                auditLog.getSuccess(),
                auditLog.getCreatedAt());
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node;
        } catch (Exception e) {
            return json;
        }
    }

    /**
     * Which roles may read audit history. Narrower than general report
     * access: operational history is restricted to finance-responsible roles.
     */
    public static boolean canReadAudit(MembershipRole role) {
        return role == MembershipRole.OWNER
                || role == MembershipRole.ADMIN
                || role == MembershipRole.ACCOUNTANT;
    }
}
