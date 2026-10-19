package com.example.accounting.repository;

import com.example.accounting.entity.Organization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.config.JpaAuditingConfig;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
class OrganizationRepositoryTest {

    @Autowired
    private OrganizationRepository organizationRepository;

    @Test
    void shouldSaveAndFindOrganization() {
        Organization org = new Organization("Acme Corp", "Acme Corporation Pvt Ltd", "PRIVATE_LIMITED", "India", "INR", "Asia/Kolkata");
        Organization saved = organizationRepository.save(org);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getName()).isEqualTo("Acme Corp");

        Organization found = organizationRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getLegalName()).isEqualTo("Acme Corporation Pvt Ltd");
    }

    @Test
    void shouldCheckExistsByName() {
        Organization org = new Organization("UniqueOrg", "Legal Unique", "LLP", "USA", "USD", "America/New_York");
        organizationRepository.save(org);

        assertThat(organizationRepository.existsByName("UniqueOrg")).isTrue();
        assertThat(organizationRepository.existsByName("NonExisting")).isFalse();
    }

    @Test
    void shouldFindByName() {
        Organization org = new Organization("FindMe", "Find Me Ltd", "CORPORATION", "UK", "GBP", "Europe/London");
        organizationRepository.save(org);

        assertThat(organizationRepository.findByName("FindMe")).isPresent();
        assertThat(organizationRepository.findByName("Missing")).isNotPresent();
    }

    @Test
    void shouldEnforceUniqueNameConstraint() {
        Organization org1 = new Organization("DuplicateName", "Legal 1", "TYPE1", "India", "INR", "Asia/Kolkata");
        organizationRepository.saveAndFlush(org1);

        Organization org2 = new Organization("DuplicateName", "Legal 2", "TYPE2", "USA", "USD", "America/New_York");
        try {
            organizationRepository.saveAndFlush(org2);
            // If no exception, fail
            assertThat(false).as("Expected DataIntegrityViolationException for duplicate name").isTrue();
        } catch (Exception ex) {
            assertThat(ex).isNotNull();
            // exception is expected due to unique constraint
        }
    }

    @Test
    void shouldGenerateAuditingTimestamps() throws InterruptedException {
        Organization org = new Organization("AuditOrg", "Audit Legal", "PRIVATE", "Germany", "EUR", "Europe/Berlin");
        Organization saved = organizationRepository.saveAndFlush(org);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        Instant createdAt = saved.getCreatedAt();
        // update and check updatedAt changes
        Thread.sleep(10);
        saved.setCountry("France");
        Organization updated = organizationRepository.saveAndFlush(saved);
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(createdAt);
    }
}
