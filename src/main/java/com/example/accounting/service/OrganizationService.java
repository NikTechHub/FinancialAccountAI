package com.example.accounting.service;

import com.example.accounting.cache.CacheKeyBuilder;
import com.example.accounting.cache.CacheNames;
import com.example.accounting.cache.TenantCacheService;
import com.example.accounting.audit.AuditAction;
import com.example.accounting.audit.AuditCommand;
import com.example.accounting.audit.AuditLogService;
import com.example.accounting.audit.AuditResourceType;
import com.example.accounting.audit.AuditSnapshots;
import com.example.accounting.dto.CreateOrganizationRequest;
import com.example.accounting.dto.OrganizationResponse;
import com.example.accounting.entity.MembershipRole;
import com.example.accounting.entity.Organization;
import com.example.accounting.entity.OrganizationMembership;
import com.example.accounting.entity.User;
import com.example.accounting.exception.OrganizationAlreadyExistsException;
import com.example.accounting.exception.OrganizationNotFoundException;
import com.example.accounting.exception.UserNotFoundException;
import com.example.accounting.repository.OrganizationMembershipRepository;
import com.example.accounting.repository.OrganizationRepository;
import com.example.accounting.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final TenantCacheService tenantCache;
    private final AuditLogService auditLogService;

    public OrganizationService(OrganizationRepository organizationRepository,
                              OrganizationMembershipRepository membershipRepository,
                              UserRepository userRepository,
                              TenantCacheService tenantCache,
                              AuditLogService auditLogService) {
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.tenantCache = tenantCache;
        this.auditLogService = auditLogService;
    }

    // Legacy method for backward compatibility (used by some tests without auth)
    @Transactional
    public OrganizationResponse createOrganization(CreateOrganizationRequest request) {
        return createOrganization(request, null);
    }

    @Transactional
    public OrganizationResponse createOrganization(CreateOrganizationRequest request, UUID creatorUserId) {
        if (organizationRepository.existsByName(request.name())) {
            throw new OrganizationAlreadyExistsException(request.name());
        }

        Organization organization = new Organization(
                request.name(),
                request.legalName(),
                request.businessType(),
                request.country(),
                request.currency(),
                request.timezone()
        );

        try {
            Organization saved = organizationRepository.save(organization);

            // If creator is authenticated, make them OWNER (tenant isolation root)
            if (creatorUserId != null) {
                User creator = userRepository.findById(creatorUserId)
                        .orElseThrow(() -> new UserNotFoundException(creatorUserId));
                // avoid duplicate if already OWNER via register flow
                if (!membershipRepository.existsByUserIdAndOrganizationId(creator.getId(), saved.getId())) {
                    OrganizationMembership membership = new OrganizationMembership(creator, saved, MembershipRole.OWNER);
                    membershipRepository.save(membership);
                }
            }

            auditLogService.record(new AuditCommand(AuditAction.ORGANIZATION_CREATED,
                    AuditResourceType.ORGANIZATION, saved.getId(), creatorUserId, saved.getId(),
                    "Organization created",
                    null, auditLogService.toJson(AuditSnapshots.organization(saved.getName(),
                            saved.getBusinessType(), saved.getCountry(), saved.getCurrency(),
                            saved.getTimezone())),
                    auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));

            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new OrganizationAlreadyExistsException(request.name());
        }
    }

    @Transactional(readOnly = true)
    public OrganizationResponse getOrganizationById(UUID id) {
        // NOTE: callers (OrganizationController) validate membership via
        // OrganizationAccessService.requireMembership BEFORE this call, so the
        // tenant-safe key below is only built after tenant access is validated.
        // Only data safe for authenticated members of this org is cached.
        String key = CacheKeyBuilder.organization(id);
        var cached = tenantCache.get(CacheNames.ORGANIZATIONS, key, OrganizationResponse.class);
        if (cached.isPresent()) {
            return cached.get();
        }
        Organization organization = organizationRepository.findById(id)
                .orElseThrow(() -> new OrganizationNotFoundException(id));
        OrganizationResponse response = toResponse(organization);
        tenantCache.put(CacheNames.ORGANIZATIONS, key, response);
        return response;
    }

    /**
     * Explicitly invalidate an organization's metadata cache.
     * Currently organizations are immutable after creation, but this is
     * provided for future metadata updates.
     */
    public void evictOrganizationCache(UUID organizationId) {
        tenantCache.evict(CacheNames.ORGANIZATIONS, CacheKeyBuilder.organization(organizationId));
    }

    private OrganizationResponse toResponse(Organization organization) {
        return new OrganizationResponse(
                organization.getId(),
                organization.getName(),
                organization.getLegalName(),
                organization.getBusinessType(),
                organization.getCountry(),
                organization.getCurrency(),
                organization.getTimezone(),
                organization.getCreatedAt(),
                organization.getUpdatedAt()
        );
    }
}
