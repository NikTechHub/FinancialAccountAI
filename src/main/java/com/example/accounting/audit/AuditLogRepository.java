package com.example.accounting.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * Tenant-scoped audit search. Ordering is fixed (newest first, ID
     * tie-break) so pagination is deterministic regardless of client sort.
     */
    @Query("SELECT a FROM AuditLog a "
            + "WHERE a.organization.id IN :orgIds "
            + "AND (:action IS NULL OR a.action = :action) "
            + "AND (:resourceType IS NULL OR a.resourceType = :resourceType) "
            + "AND (:resourceId IS NULL OR a.resourceId = :resourceId) "
            + "AND (:actorUserId IS NULL OR a.actorUser.id = :actorUserId) "
            + "AND (:success IS NULL OR a.success = :success) "
            + "AND a.createdAt >= :from AND a.createdAt < :to "
            + "ORDER BY a.createdAt DESC, a.id ASC")
    Page<AuditLog> search(@Param("orgIds") List<UUID> orgIds,
                          @Param("action") AuditAction action,
                          @Param("resourceType") AuditResourceType resourceType,
                          @Param("resourceId") UUID resourceId,
                          @Param("actorUserId") UUID actorUserId,
                          @Param("success") Boolean success,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable pageable);

    long countByOrganization_IdAndAction(UUID organizationId, AuditAction action);
}
