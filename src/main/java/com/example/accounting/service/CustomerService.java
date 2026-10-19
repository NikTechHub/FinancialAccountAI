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
import com.example.accounting.dto.CreateCustomerRequest;
import com.example.accounting.dto.CustomerResponse;
import com.example.accounting.dto.UpdateCustomerRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.example.accounting.entity.Customer;
import com.example.accounting.entity.MembershipRole;
import com.example.accounting.entity.Organization;
import com.example.accounting.exception.CustomerAlreadyExistsException;
import com.example.accounting.exception.CustomerNotFoundException;
import com.example.accounting.exception.OrganizationNotFoundException;
import com.example.accounting.repository.CustomerRepository;
import com.example.accounting.repository.OrganizationMembershipRepository;
import com.example.accounting.repository.OrganizationRepository;
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
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationAccessService organizationAccessService;
    private final TenantCacheService tenantCache;
    private final AuditLogService auditLogService;

    public CustomerService(CustomerRepository customerRepository,
                           OrganizationRepository organizationRepository,
                           OrganizationMembershipRepository membershipRepository,
                           OrganizationAccessService organizationAccessService,
                           TenantCacheService tenantCache,
                           AuditLogService auditLogService) {
        this.customerRepository = customerRepository;
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
        UUID primaryOrgId = orgIds.get(0);
        return organizationRepository.findById(primaryOrgId)
                .orElseThrow(() -> new OrganizationNotFoundException(primaryOrgId));
    }

    @Transactional
    public CustomerResponse createCustomer(UUID userId, CreateCustomerRequest request) {
        Organization org = resolveSingleOrganization(userId);

        var role = organizationAccessService.userRole(userId, org.getId()).orElse(null);
        if (role == MembershipRole.VIEWER) {
            throw new AccessDeniedException("VIEWER role cannot create customers");
        }

        if (customerRepository.existsByOrganizationIdAndName(org.getId(), request.name())) {
            throw new CustomerAlreadyExistsException(request.name());
        }

        Customer customer = new Customer(
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
            Customer saved = customerRepository.save(customer);
            tenantCache.evictOrgCategory(saved.getOrganization().getId(), CacheNames.CUSTOMERS_LIST);
            auditLogService.record(new AuditCommand(AuditAction.CUSTOMER_CREATED, AuditResourceType.CUSTOMER,
                    saved.getOrganization().getId(), userId, saved.getId(), "Customer created",
                    null, auditLogService.toJson(AuditSnapshots.customer(saved.getName(), saved.getEmail(),
                            saved.getPhone(), saved.getAddress(), saved.getTaxIdentifier(),
                            saved.getPaymentTerms(), saved.getActive())),
                    auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new CustomerAlreadyExistsException(request.name());
        }
    }

    @Transactional(readOnly = true)
    public CustomerResponse getCustomer(UUID userId, UUID customerId) {
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            throw new CustomerNotFoundException(customerId);
        }
        for (UUID orgId : orgIds) {
            var cached = tenantCache.get(CacheNames.CUSTOMERS_ITEM,
                    CacheKeyBuilder.customerItem(orgId, customerId), CustomerResponse.class);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));
        if (!orgIds.contains(customer.getOrganization().getId())) {
            throw new CustomerNotFoundException(customerId);
        }
        CustomerResponse response = toResponse(customer);
        tenantCache.put(CacheNames.CUSTOMERS_ITEM,
                CacheKeyBuilder.customerItem(customer.getOrganization().getId(), customerId), response);
        return response;
    }

    @Transactional(readOnly = true)
    public Page<CustomerResponse> listCustomers(UUID userId, String search, Pageable pageable) {
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            return Page.empty(pageable);
        }
        String listKey = CacheKeyBuilder.customerList(orgIds, search, pageable);
        var cached = tenantCache.get(CacheNames.CUSTOMERS_LIST, listKey,
                new TypeReference<CachedPage<CustomerResponse>>() {
                });
        if (cached.isPresent()) {
            return cached.get().toPage(pageable);
        }
        Page<Customer> page;
        if (StringUtils.hasText(search)) {
            page = customerRepository.findByOrganizationIdInAndNameContainingIgnoreCaseAndActiveTrue(
                    orgIds, search.trim(), pageable);
        } else {
            page = customerRepository.findByOrganizationIdInAndActiveTrue(orgIds, pageable);
        }
        Page<CustomerResponse> result = page.map(this::toResponse);
        tenantCache.put(CacheNames.CUSTOMERS_LIST, listKey, CachedPage.from(result));
        return result;
    }

    @Transactional
    public CustomerResponse updateCustomer(UUID userId, UUID customerId, UpdateCustomerRequest request) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));

        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (!orgIds.contains(customer.getOrganization().getId())) {
            throw new CustomerNotFoundException(customerId);
        }

        var role = organizationAccessService.userRole(userId, customer.getOrganization().getId()).orElse(null);
        if (role == MembershipRole.VIEWER) {
            throw new AccessDeniedException("VIEWER role cannot update customers");
        }

        var before = AuditSnapshots.customer(customer.getName(), customer.getEmail(), customer.getPhone(),
                customer.getAddress(), customer.getTaxIdentifier(), customer.getPaymentTerms(), customer.getActive());

        if (StringUtils.hasText(request.name()) && !request.name().equals(customer.getName())) {
            if (customerRepository.existsByOrganizationIdAndName(customer.getOrganization().getId(), request.name())) {
                throw new CustomerAlreadyExistsException(request.name());
            }
            customer.setName(request.name());
        }
        if (request.email() != null) customer.setEmail(normalizeEmpty(request.email()));
        if (request.phone() != null) customer.setPhone(normalizeEmpty(request.phone()));
        if (request.address() != null) customer.setAddress(normalizeEmpty(request.address()));
        if (request.taxIdentifier() != null) customer.setTaxIdentifier(normalizeEmpty(request.taxIdentifier()));
        if (request.paymentTerms() != null) customer.setPaymentTerms(normalizeEmpty(request.paymentTerms()));
        if (request.active() != null) customer.setActive(request.active());

        try {
            Customer saved = customerRepository.save(customer);
            UUID orgId = saved.getOrganization().getId();
            tenantCache.evict(CacheNames.CUSTOMERS_ITEM, CacheKeyBuilder.customerItem(orgId, customerId));
            tenantCache.evictOrgCategory(orgId, CacheNames.CUSTOMERS_LIST);
            auditLogService.record(new AuditCommand(AuditAction.CUSTOMER_UPDATED, AuditResourceType.CUSTOMER,
                    orgId, userId, saved.getId(), "Customer updated",
                    auditLogService.toJson(before),
                    auditLogService.toJson(AuditSnapshots.customer(saved.getName(), saved.getEmail(),
                            saved.getPhone(), saved.getAddress(), saved.getTaxIdentifier(),
                            saved.getPaymentTerms(), saved.getActive())),
                    auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new CustomerAlreadyExistsException(request.name() != null ? request.name() : customer.getName());
        }
    }

    @Transactional
    public void deactivateCustomer(UUID userId, UUID customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (!orgIds.contains(customer.getOrganization().getId())) {
            throw new CustomerNotFoundException(customerId);
        }
        organizationAccessService.requireRole(userId, customer.getOrganization().getId(), MembershipRole.OWNER, MembershipRole.ADMIN);
        var before = AuditSnapshots.customer(customer.getName(), customer.getEmail(), customer.getPhone(),
                customer.getAddress(), customer.getTaxIdentifier(), customer.getPaymentTerms(), customer.getActive());
        customer.setActive(false);
        customerRepository.save(customer);
        UUID orgId = customer.getOrganization().getId();
        tenantCache.evict(CacheNames.CUSTOMERS_ITEM, CacheKeyBuilder.customerItem(orgId, customerId));
        tenantCache.evictOrgCategory(orgId, CacheNames.CUSTOMERS_LIST);
        auditLogService.record(new AuditCommand(AuditAction.CUSTOMER_DEACTIVATED, AuditResourceType.CUSTOMER,
                orgId, userId, customer.getId(), "Customer deactivated",
                auditLogService.toJson(before),
                auditLogService.toJson(AuditSnapshots.customer(customer.getName(), customer.getEmail(),
                        customer.getPhone(), customer.getAddress(), customer.getTaxIdentifier(),
                        customer.getPaymentTerms(), false)),
                auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
    }

    private String normalizeEmpty(String value) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private CustomerResponse toResponse(Customer c) {
        return new CustomerResponse(
                c.getId(),
                c.getOrganization().getId(),
                c.getOrganization().getName(),
                c.getName(),
                c.getEmail(),
                c.getPhone(),
                c.getAddress(),
                c.getTaxIdentifier(),
                c.getPaymentTerms(),
                c.getActive(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}
