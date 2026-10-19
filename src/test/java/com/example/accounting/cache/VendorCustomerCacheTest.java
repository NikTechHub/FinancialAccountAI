package com.example.accounting.cache;

import com.example.accounting.dto.CreateCustomerRequest;
import com.example.accounting.dto.CreateVendorRequest;
import com.example.accounting.entity.Customer;
import com.example.accounting.entity.MembershipRole;
import com.example.accounting.entity.Organization;
import com.example.accounting.entity.OrganizationMembership;
import com.example.accounting.entity.User;
import com.example.accounting.entity.Vendor;
import com.example.accounting.repository.CustomerRepository;
import com.example.accounting.repository.OrganizationMembershipRepository;
import com.example.accounting.repository.OrganizationRepository;
import com.example.accounting.repository.VendorRepository;
import com.example.accounting.security.OrganizationAccessService;
import com.example.accounting.service.CustomerService;
import com.example.accounting.service.VendorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hit/miss/invalidation verification for vendor and customer list caching.
 */
@ExtendWith(MockitoExtension.class)
class VendorCustomerCacheTest {

    @Mock
    private VendorRepository vendorRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private OrganizationMembershipRepository membershipRepository;
    @Mock
    private OrganizationAccessService organizationAccessService;
    @Mock
    private com.example.accounting.audit.AuditLogService auditLogService;

    private TenantCacheService tenantCache;
    private VendorService vendorService;
    private CustomerService customerService;

    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private Organization org;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager(CacheNames.all());
        cacheManager.setAllowNullValues(false);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ObjectProvider<org.springframework.data.redis.core.RedisTemplate<String, Object>> provider =
                mock(ObjectProvider.class);
        Mockito.lenient().when(provider.getIfAvailable()).thenReturn(null);
        tenantCache = new TenantCacheService(cacheManager, new RedisProperties(), objectMapper, provider);
        vendorService = new VendorService(vendorRepository, organizationRepository,
                membershipRepository, organizationAccessService, tenantCache, auditLogService);
        customerService = new CustomerService(customerRepository, organizationRepository,
                membershipRepository, organizationAccessService, tenantCache, auditLogService);

        org = new Organization("Org", null, null, null, null, null);
        org.setId(orgId);
        User user = new User("First", "Last", "u@example.com", "hash", true);
        // Both services query findByUserId; lenient stubbing via shared mock.
        Mockito.lenient()
                .when(membershipRepository.findByUserId(userId))
                .thenReturn(List.of(new OrganizationMembership(user, org, MembershipRole.OWNER)));
        Mockito.lenient()
                .when(organizationAccessService.userRole(userId, orgId))
                .thenReturn(Optional.of(MembershipRole.OWNER));
    }

    @Test
    void vendorListSecondCallUsesCacheAndCreateInvalidates() {
        Pageable pageable = PageRequest.of(0, 20);
        Vendor v = new Vendor(org, "Acme", null, null, null, null, null, true);
        v.setId(UUID.randomUUID());
        when(vendorRepository.findByOrganizationIdInAndActiveTrue(anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(v)));

        var first = vendorService.listVendors(userId, null, pageable);
        var second = vendorService.listVendors(userId, null, pageable);
        assertThat(first.getTotalElements()).isEqualTo(1);
        assertThat(second.getContent()).isEqualTo(first.getContent());
        verify(vendorRepository, times(1)).findByOrganizationIdInAndActiveTrue(anyList(), any(Pageable.class));

        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(vendorRepository.existsByOrganizationIdAndName(orgId, "NewCo")).thenReturn(false);
        Vendor saved = new Vendor(org, "NewCo", null, null, null, null, null, true);
        saved.setId(UUID.randomUUID());
        when(vendorRepository.save(any(Vendor.class))).thenReturn(saved);
        vendorService.createVendor(userId, new CreateVendorRequest("NewCo", null, null, null, null, null));

        vendorService.listVendors(userId, null, pageable);
        verify(vendorRepository, times(2)).findByOrganizationIdInAndActiveTrue(anyList(), any(Pageable.class));
    }

    @Test
    void customerListSecondCallUsesCacheAndCreateInvalidates() {
        Pageable pageable = PageRequest.of(0, 20);
        Customer c = new Customer(org, "Globex", null, null, null, null, null, true);
        c.setId(UUID.randomUUID());
        when(customerRepository.findByOrganizationIdInAndActiveTrue(anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(c)));

        var first = customerService.listCustomers(userId, null, pageable);
        var second = customerService.listCustomers(userId, null, pageable);
        assertThat(first.getTotalElements()).isEqualTo(1);
        assertThat(second.getContent()).isEqualTo(first.getContent());
        verify(customerRepository, times(1)).findByOrganizationIdInAndActiveTrue(anyList(), any(Pageable.class));

        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(customerRepository.existsByOrganizationIdAndName(orgId, "NewCustomer")).thenReturn(false);
        Customer saved = new Customer(org, "NewCustomer", null, null, null, null, null, true);
        saved.setId(UUID.randomUUID());
        when(customerRepository.save(any(Customer.class))).thenReturn(saved);
        customerService.createCustomer(userId,
                new CreateCustomerRequest("NewCustomer", null, null, null, null, null));

        customerService.listCustomers(userId, null, pageable);
        verify(customerRepository, times(2)).findByOrganizationIdInAndActiveTrue(anyList(), any(Pageable.class));
    }

    @Test
    void vendorSearchKeysDifferByOrg() {
        Pageable pageable = PageRequest.of(0, 20);
        UUID otherOrg = UUID.randomUUID();
        String keyA = CacheKeyBuilder.vendorList(List.of(orgId), "Acme", pageable);
        String keyB = CacheKeyBuilder.vendorList(List.of(otherOrg), "Acme", pageable);
        assertThat(keyA).isNotEqualTo(keyB);
    }
}
