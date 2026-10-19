package com.example.accounting.service;

import com.example.accounting.audit.AuditAction;
import com.example.accounting.audit.AuditCommand;
import com.example.accounting.audit.AuditLogService;
import com.example.accounting.audit.AuditResourceType;
import com.example.accounting.audit.AuditSnapshots;
import com.example.accounting.cache.CacheKeyBuilder;
import com.example.accounting.cache.CacheNames;
import com.example.accounting.cache.CachedPage;
import com.example.accounting.cache.TenantCacheService;
import com.example.accounting.dto.CreateVendorRequest;
import com.example.accounting.dto.UpdateVendorRequest;
import com.example.accounting.dto.VendorResponse;
import com.example.accounting.entity.MembershipRole;
import com.fasterxml.jackson.core.type.TypeReference;
import com.example.accounting.entity.Organization;
import com.example.accounting.entity.Vendor;
import com.example.accounting.exception.OrganizationNotFoundException;
import com.example.accounting.exception.VendorAlreadyExistsException;
import com.example.accounting.exception.VendorNotFoundException;
import com.example.accounting.repository.OrganizationMembershipRepository;
import com.example.accounting.repository.OrganizationRepository;
import com.example.accounting.repository.VendorRepository;
import com.example.accounting.security.OrganizationAccessService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class VendorService {

    private final VendorRepository vendorRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationAccessService organizationAccessService;
    private final TenantCacheService tenantCache;
    private final AuditLogService auditLogService;

    public VendorService(VendorRepository vendorRepository,
                         OrganizationRepository organizationRepository,
                         OrganizationMembershipRepository membershipRepository,
                         OrganizationAccessService organizationAccessService,
                         TenantCacheService tenantCache,
                         AuditLogService auditLogService) {
        this.vendorRepository = vendorRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.organizationAccessService = organizationAccessService;
        this.tenantCache = tenantCache;
        this.auditLogService = auditLogService;
    }

    private List<UUID> accessibleOrganizationIds(UUID userId) {
        return membershipRepository.findByUserId(userId).stream()
                .map(m -> m.getOrganization().getId())
                .collect(Collectors.toList());
    }

    private Organization resolveSingleOrganization(UUID userId) {
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            throw new OrganizationNotFoundException(UUID.randomUUID());
        }
        // If user belongs to multiple orgs, use the first. Listing will use all orgs.
        // This enforces tenant isolation without trusting client-supplied org ID.
        UUID primaryOrgId = orgIds.get(0);
        return organizationRepository.findById(primaryOrgId)
                .orElseThrow(() -> new OrganizationNotFoundException(primaryOrgId));
    }

    @Transactional
    public VendorResponse createVendor(UUID userId, CreateVendorRequest request) {
        Organization org = resolveSingleOrganization(userId);

        // RBAC: VIEWER cannot create
        var role = organizationAccessService.userRole(userId, org.getId()).orElse(null);
        if (role == MembershipRole.VIEWER) {
            throw new AccessDeniedException("VIEWER role cannot create vendors");
        }

        if (vendorRepository.existsByOrganizationIdAndName(org.getId(), request.name())) {
            throw new VendorAlreadyExistsException(request.name());
        }

        Vendor vendor = new Vendor(
                org,
                request.name(),
                normalizeEmpty(request.email()),
                normalizeEmpty(request.phone()),
                normalizeEmpty(request.address()),
                normalizeEmpty(request.taxIdentifier()),
                normalizeEmpty(request.paymentTerms()),
                true
        );

        try {
            Vendor saved = vendorRepository.save(vendor);
            tenantCache.evictOrgCategory(saved.getOrganization().getId(), CacheNames.VENDORS_LIST);
            auditLogService.record(new AuditCommand(AuditAction.VENDOR_CREATED, AuditResourceType.VENDOR,
                    saved.getOrganization().getId(), userId, saved.getId(), "Vendor created",
                    null, auditLogService.toJson(AuditSnapshots.vendor(saved.getName(), saved.getEmail(),
                            saved.getPhone(), saved.getAddress(), saved.getTaxIdentifier(),
                            saved.getPaymentTerms(), saved.getActive())),
                    auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new VendorAlreadyExistsException(request.name());
        }
    }

    @Transactional(readOnly = true)
    public VendorResponse getVendor(UUID userId, UUID vendorId) {
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            throw new VendorNotFoundException(vendorId);
        }
        for (UUID orgId : orgIds) {
            var cached = tenantCache.get(CacheNames.VENDORS_ITEM,
                    CacheKeyBuilder.vendorItem(orgId, vendorId), VendorResponse.class);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        // find vendor where org in accessible
        Vendor vendor = vendorRepository.findById(vendorId)
                .orElseThrow(() -> new VendorNotFoundException(vendorId));
        if (!orgIds.contains(vendor.getOrganization().getId())) {
            throw new VendorNotFoundException(vendorId);
        }
        VendorResponse response = toResponse(vendor);
        tenantCache.put(CacheNames.VENDORS_ITEM,
                CacheKeyBuilder.vendorItem(vendor.getOrganization().getId(), vendorId), response);
        return response;
    }

    @Transactional(readOnly = true)
    public Page<VendorResponse> listVendors(UUID userId, String search, Pageable pageable) {
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            return Page.empty(pageable);
        }
        String listKey = CacheKeyBuilder.vendorList(orgIds, search, pageable);
        var cached = tenantCache.get(CacheNames.VENDORS_LIST, listKey,
                new TypeReference<CachedPage<VendorResponse>>() {
                });
        if (cached.isPresent()) {
            return cached.get().toPage(pageable);
        }
        Page<Vendor> page;
        if (StringUtils.hasText(search)) {
            page = vendorRepository.findByOrganizationIdInAndNameContainingIgnoreCaseAndActiveTrue(
                    orgIds, search.trim(), pageable);
        } else {
            page = vendorRepository.findByOrganizationIdInAndActiveTrue(orgIds, pageable);
        }
        Page<VendorResponse> result = page.map(this::toResponse);
        tenantCache.put(CacheNames.VENDORS_LIST, listKey, CachedPage.from(result));
        return result;
    }

    @Transactional
    public VendorResponse updateVendor(UUID userId, UUID vendorId, UpdateVendorRequest request) {
        Vendor vendor = vendorRepository.findById(vendorId)
                .orElseThrow(() -> new VendorNotFoundException(vendorId));

        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (!orgIds.contains(vendor.getOrganization().getId())) {
            throw new VendorNotFoundException(vendorId);
        }

        // RBAC: VIEWER cannot update
        var role = organizationAccessService.userRole(userId, vendor.getOrganization().getId()).orElse(null);
        if (role == MembershipRole.VIEWER) {
            throw new AccessDeniedException("VIEWER role cannot update vendors");
        }

        var before = AuditSnapshots.vendor(vendor.getName(), vendor.getEmail(), vendor.getPhone(),
                vendor.getAddress(), vendor.getTaxIdentifier(), vendor.getPaymentTerms(), vendor.getActive());

        // If name is being updated, check duplicate within same org
        if (StringUtils.hasText(request.name()) && !request.name().equals(vendor.getName())) {
            if (vendorRepository.existsByOrganizationIdAndName(vendor.getOrganization().getId(), request.name())) {
                throw new VendorAlreadyExistsException(request.name());
            }
            vendor.setName(request.name());
        }
        if (request.email() != null) vendor.setEmail(normalizeEmpty(request.email()));
        if (request.phone() != null) vendor.setPhone(normalizeEmpty(request.phone()));
        if (request.address() != null) vendor.setAddress(normalizeEmpty(request.address()));
        if (request.taxIdentifier() != null) vendor.setTaxIdentifier(normalizeEmpty(request.taxIdentifier()));
        if (request.paymentTerms() != null) vendor.setPaymentTerms(normalizeEmpty(request.paymentTerms()));
        if (request.active() != null) vendor.setActive(request.active());

        try {
            Vendor saved = vendorRepository.save(vendor);
            UUID orgId = saved.getOrganization().getId();
            tenantCache.evict(CacheNames.VENDORS_ITEM, CacheKeyBuilder.vendorItem(orgId, vendorId));
            tenantCache.evictOrgCategory(orgId, CacheNames.VENDORS_LIST);
            auditLogService.record(new AuditCommand(AuditAction.VENDOR_UPDATED, AuditResourceType.VENDOR,
                    orgId, userId, saved.getId(), "Vendor updated",
                    auditLogService.toJson(before),
                    auditLogService.toJson(AuditSnapshots.vendor(saved.getName(), saved.getEmail(),
                            saved.getPhone(), saved.getAddress(), saved.getTaxIdentifier(),
                            saved.getPaymentTerms(), saved.getActive())),
                    auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new VendorAlreadyExistsException(request.name() != null ? request.name() : vendor.getName());
        }
    }

    @Transactional
    public void deactivateVendor(UUID userId, UUID vendorId) {
        Vendor vendor = vendorRepository.findById(vendorId)
                .orElseThrow(() -> new VendorNotFoundException(vendorId));
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (!orgIds.contains(vendor.getOrganization().getId())) {
            throw new VendorNotFoundException(vendorId);
        }
        // RBAC: Only OWNER/ADMIN can deactivate
        organizationAccessService.requireRole(userId, vendor.getOrganization().getId(), MembershipRole.OWNER, MembershipRole.ADMIN);
        var before = AuditSnapshots.vendor(vendor.getName(), vendor.getEmail(), vendor.getPhone(),
                vendor.getAddress(), vendor.getTaxIdentifier(), vendor.getPaymentTerms(), vendor.getActive());
        vendor.setActive(false);
        vendorRepository.save(vendor);
        UUID orgId = vendor.getOrganization().getId();
        tenantCache.evict(CacheNames.VENDORS_ITEM, CacheKeyBuilder.vendorItem(orgId, vendorId));
        tenantCache.evictOrgCategory(orgId, CacheNames.VENDORS_LIST);
        auditLogService.record(new AuditCommand(AuditAction.VENDOR_DEACTIVATED, AuditResourceType.VENDOR,
                orgId, userId, vendor.getId(), "Vendor deactivated",
                auditLogService.toJson(before),
                auditLogService.toJson(AuditSnapshots.vendor(vendor.getName(), vendor.getEmail(),
                        vendor.getPhone(), vendor.getAddress(), vendor.getTaxIdentifier(),
                        vendor.getPaymentTerms(), false)),
                auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
    }

    private String normalizeEmpty(String value) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private VendorResponse toResponse(Vendor v) {
        return new VendorResponse(
                v.getId(),
                v.getOrganization().getId(),
                v.getOrganization().getName(),
                v.getName(),
                v.getEmail(),
                v.getPhone(),
                v.getAddress(),
                v.getTaxIdentifier(),
                v.getPaymentTerms(),
                v.getActive(),
                v.getCreatedAt(),
                v.getUpdatedAt()
        );
    }
}
