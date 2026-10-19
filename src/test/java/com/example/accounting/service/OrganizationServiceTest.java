package com.example.accounting.service;

import com.example.accounting.dto.CreateOrganizationRequest;
import com.example.accounting.dto.OrganizationResponse;
import com.example.accounting.cache.TenantCacheService;
import com.example.accounting.entity.Organization;
import com.example.accounting.exception.OrganizationAlreadyExistsException;
import com.example.accounting.exception.OrganizationNotFoundException;
import com.example.accounting.repository.OrganizationMembershipRepository;
import com.example.accounting.repository.OrganizationRepository;
import com.example.accounting.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private OrganizationMembershipRepository membershipRepository;

    @Mock
    private UserRepository userRepository;

    // Mocked cache: default Mockito answers return Optional.empty() for get(),
    // so these tests exercise the database fallback path (Redis-disabled behavior).
    @Mock
    private TenantCacheService tenantCache;

    @Mock
    private com.example.accounting.audit.AuditLogService auditLogService;

    @InjectMocks
    private OrganizationService organizationService;

    @Test
    void shouldCreateOrganizationSuccessfully() {
        CreateOrganizationRequest request = new CreateOrganizationRequest(
                "Test Org", "Test Legal", "PRIVATE_LIMITED", "India", "INR", "Asia/Kolkata"
        );
        when(organizationRepository.existsByName("Test Org")).thenReturn(false);
        when(organizationRepository.save(any(Organization.class))).thenAnswer(invocation -> {
            Organization org = invocation.getArgument(0);
            org.setId(UUID.randomUUID());
            org.setCreatedAt(java.time.Instant.now());
            org.setUpdatedAt(java.time.Instant.now());
            return org;
        });

        OrganizationResponse response = organizationService.createOrganization(request);

        assertThat(response.name()).isEqualTo("Test Org");
        assertThat(response.id()).isNotNull();
        verify(organizationRepository).existsByName("Test Org");
        verify(organizationRepository).save(any(Organization.class));
    }

    @Test
    void shouldThrowWhenDuplicateNameOnCreate() {
        CreateOrganizationRequest request = new CreateOrganizationRequest(
                "Duplicate", "Legal", "LLP", "USA", "USD", "America/New_York"
        );
        when(organizationRepository.existsByName("Duplicate")).thenReturn(true);

        assertThatThrownBy(() -> organizationService.createOrganization(request))
                .isInstanceOf(OrganizationAlreadyExistsException.class)
                .hasMessageContaining("Duplicate");

        verify(organizationRepository, never()).save(any());
    }

    @Test
    void shouldReturnOrganizationWhenFound() {
        UUID id = UUID.randomUUID();
        Organization org = new Organization("Found Org", "Legal Found", "CORP", "UK", "GBP", "Europe/London");
        org.setId(id);
        org.setCreatedAt(java.time.Instant.now());
        org.setUpdatedAt(java.time.Instant.now());

        when(organizationRepository.findById(id)).thenReturn(Optional.of(org));

        OrganizationResponse response = organizationService.getOrganizationById(id);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.name()).isEqualTo("Found Org");
    }

    @Test
    void shouldThrowNotFoundWhenOrganizationMissing() {
        UUID id = UUID.randomUUID();
        when(organizationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> organizationService.getOrganizationById(id))
                .isInstanceOf(OrganizationNotFoundException.class)
                .hasMessageContaining(id.toString());
    }
}
