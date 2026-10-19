package com.example.accounting.cache;

import com.example.accounting.dto.OrganizationResponse;
import com.example.accounting.entity.Organization;
import com.example.accounting.repository.OrganizationMembershipRepository;
import com.example.accounting.repository.OrganizationRepository;
import com.example.accounting.repository.UserRepository;
import com.example.accounting.service.OrganizationService;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hit/miss/invalidation verification for organization metadata caching.
 * Uses mocked repositories with a real in-memory TenantCacheService.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationCacheTest {

    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private OrganizationMembershipRepository membershipRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private com.example.accounting.audit.AuditLogService auditLogService;

    private OrganizationService organizationService;

    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager(CacheNames.all());
        cacheManager.setAllowNullValues(false);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ObjectProvider<org.springframework.data.redis.core.RedisTemplate<String, Object>> provider =
                mock(ObjectProvider.class);
        Mockito.lenient().when(provider.getIfAvailable()).thenReturn(null);
        TenantCacheService tenantCache =
                new TenantCacheService(cacheManager, new RedisProperties(), objectMapper, provider);
        organizationService =
                new OrganizationService(organizationRepository, membershipRepository, userRepository, tenantCache, auditLogService);
    }

    @Test
    void secondLookupUsesCache() {
        Organization org = new Organization("Cached Org", null, null, null, null, null);
        org.setId(orgId);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));

        OrganizationResponse first = organizationService.getOrganizationById(orgId);
        OrganizationResponse second = organizationService.getOrganizationById(orgId);

        assertThat(first.name()).isEqualTo("Cached Org");
        assertThat(second).isEqualTo(first);
        verify(organizationRepository, times(1)).findById(orgId);
    }

    @Test
    void evictionForcesReload() {
        Organization org = new Organization("Cached Org", null, null, null, null, null);
        org.setId(orgId);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));

        organizationService.getOrganizationById(orgId);
        organizationService.evictOrganizationCache(orgId);
        organizationService.getOrganizationById(orgId);

        verify(organizationRepository, times(2)).findById(orgId);
    }
}
