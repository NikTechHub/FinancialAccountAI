package com.example.accounting.repository;

import com.example.accounting.config.JpaAuditingConfig;
import com.example.accounting.entity.Organization;
import com.example.accounting.entity.Vendor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
class VendorRepositoryTest {

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private VendorRepository vendorRepository;

    @Test
    void shouldSaveAndFindVendor() {
        Organization org = organizationRepository.save(new Organization("Vendor Org", "Legal", "LLP", "India", "INR", "Asia/Kolkata"));
        Vendor vendor = new Vendor(org, "Acme Vendor", "vendor@acme.com", "1234567890", "123 Street", "TAX123", "NET30", true);
        Vendor saved = vendorRepository.save(vendor);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getActive()).isTrue();
        assertThat(vendorRepository.findByIdAndOrganizationId(saved.getId(), org.getId())).isPresent();
    }

    @Test
    void shouldEnforceUniqueNamePerOrganization() {
        Organization org = organizationRepository.save(new Organization("Unique Org", "Legal", "LLP", "India", "INR", "Asia/Kolkata"));
        vendorRepository.save(new Vendor(org, "DuplicateVendor", "a@b.com", null, null, null, null, true));
        vendorRepository.flush();

        Vendor duplicate = new Vendor(org, "DuplicateVendor", "b@c.com", null, null, null, null, true);
        try {
            vendorRepository.saveAndFlush(duplicate);
            assertThat(false).as("Expected duplicate violation").isTrue();
        } catch (Exception ex) {
            assertThat(ex).isNotNull();
        }
    }

    @Test
    void shouldAllowSameNameAcrossDifferentOrganizations() {
        Organization org1 = organizationRepository.save(new Organization("Org1", "Legal1", "LLP", "India", "INR", "Asia/Kolkata"));
        Organization org2 = organizationRepository.save(new Organization("Org2", "Legal2", "LLP", "USA", "USD", "America/New_York"));

        vendorRepository.save(new Vendor(org1, "SameName", "a@b.com", null, null, null, null, true));
        Vendor v2 = vendorRepository.save(new Vendor(org2, "SameName", "b@c.com", null, null, null, null, true));

        assertThat(v2.getId()).isNotNull();
    }

    @Test
    void shouldSearchByName() {
        Organization org = organizationRepository.save(new Organization("Search Org", "Legal", "LLP", "India", "INR", "Asia/Kolkata"));
        vendorRepository.save(new Vendor(org, "Amazon Supplies", "a@a.com", null, null, null, null, true));
        vendorRepository.save(new Vendor(org, "Amazon Web", "b@b.com", null, null, null, null, true));
        vendorRepository.save(new Vendor(org, "Google Vendor", "c@c.com", null, null, null, null, true));

        Page<Vendor> page = vendorRepository.findByOrganizationIdInAndNameContainingIgnoreCaseAndActiveTrue(
                List.of(org.getId()), "amazon", PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void shouldPaginateActiveOnly() {
        Organization org = organizationRepository.save(new Organization("Page Org", "Legal", "LLP", "India", "INR", "Asia/Kolkata"));
        for (int i = 0; i < 5; i++) {
            vendorRepository.save(new Vendor(org, "Vendor" + i, "v" + i + "@a.com", null, null, null, null, true));
        }
        Vendor inactive = new Vendor(org, "InactiveVendor", "inactive@a.com", null, null, null, null, false);
        vendorRepository.save(inactive);

        Page<Vendor> page1 = vendorRepository.findByOrganizationIdInAndActiveTrue(List.of(org.getId()), PageRequest.of(0, 2));
        assertThat(page1.getTotalElements()).isEqualTo(5);
        assertThat(page1.getSize()).isEqualTo(2);
        assertThat(page1.getContent()).hasSize(2);

        Page<Vendor> page2 = vendorRepository.findByOrganizationIdInAndActiveTrue(List.of(org.getId()), PageRequest.of(1, 2));
        assertThat(page2.getContent()).hasSize(2);
    }
}
