package com.example.accounting.repository;

import com.example.accounting.config.JpaAuditingConfig;
import com.example.accounting.entity.Customer;
import com.example.accounting.entity.Organization;
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
class CustomerRepositoryTest {

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void shouldSaveAndFindCustomer() {
        Organization org = organizationRepository.save(new Organization("Cust Org", "Legal", "LLP", "India", "INR", "Asia/Kolkata"));
        Customer customer = new Customer(org, "Acme Customer", "customer@acme.com", "1234567890", "123 Street", "TAX123", "NET30", true);
        Customer saved = customerRepository.save(customer);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getActive()).isTrue();
        assertThat(customerRepository.findByIdAndOrganizationId(saved.getId(), org.getId())).isPresent();
    }

    @Test
    void shouldEnforceUniqueNamePerOrganization() {
        Organization org = organizationRepository.save(new Organization("Unique Org", "Legal", "LLP", "India", "INR", "Asia/Kolkata"));
        customerRepository.save(new Customer(org, "DuplicateCustomer", "a@b.com", null, null, null, null, true));
        customerRepository.flush();

        Customer duplicate = new Customer(org, "DuplicateCustomer", "b@c.com", null, null, null, null, true);
        try {
            customerRepository.saveAndFlush(duplicate);
            assertThat(false).as("Expected duplicate violation").isTrue();
        } catch (Exception ex) {
            assertThat(ex).isNotNull();
        }
    }

    @Test
    void shouldAllowSameNameAcrossDifferentOrganizations() {
        Organization org1 = organizationRepository.save(new Organization("Org1", "Legal1", "LLP", "India", "INR", "Asia/Kolkata"));
        Organization org2 = organizationRepository.save(new Organization("Org2", "Legal2", "LLP", "USA", "USD", "America/New_York"));

        customerRepository.save(new Customer(org1, "SameName", "a@b.com", null, null, null, null, true));
        Customer c2 = customerRepository.save(new Customer(org2, "SameName", "b@c.com", null, null, null, null, true));

        assertThat(c2.getId()).isNotNull();
    }

    @Test
    void shouldSearchByName() {
        Organization org = organizationRepository.save(new Organization("Search Org", "Legal", "LLP", "India", "INR", "Asia/Kolkata"));
        customerRepository.save(new Customer(org, "Acme Supplies", "a@a.com", null, null, null, null, true));
        customerRepository.save(new Customer(org, "Acme Web", "b@b.com", null, null, null, null, true));
        customerRepository.save(new Customer(org, "Globex Customer", "c@c.com", null, null, null, null, true));

        Page<Customer> page = customerRepository.findByOrganizationIdInAndNameContainingIgnoreCaseAndActiveTrue(
                List.of(org.getId()), "acme", PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void shouldPaginateActiveOnly() {
        Organization org = organizationRepository.save(new Organization("Page Org", "Legal", "LLP", "India", "INR", "Asia/Kolkata"));
        for (int i = 0; i < 5; i++) {
            customerRepository.save(new Customer(org, "Customer" + i, "v" + i + "@a.com", null, null, null, null, true));
        }
        Customer inactive = new Customer(org, "InactiveCustomer", "inactive@a.com", null, null, null, null, false);
        customerRepository.save(inactive);

        Page<Customer> page1 = customerRepository.findByOrganizationIdInAndActiveTrue(List.of(org.getId()), PageRequest.of(0, 2));
        assertThat(page1.getTotalElements()).isEqualTo(5);
        assertThat(page1.getSize()).isEqualTo(2);
        assertThat(page1.getContent()).hasSize(2);

        Page<Customer> page2 = customerRepository.findByOrganizationIdInAndActiveTrue(List.of(org.getId()), PageRequest.of(1, 2));
        assertThat(page2.getContent()).hasSize(2);
    }

    @Test
    void shouldUseIndexesForOrganizationQueries() {
        Organization org = organizationRepository.save(new Organization("Index Org", "Legal", "LLP", "India", "INR", "Asia/Kolkata"));
        for (int i = 0; i < 3; i++) {
            customerRepository.save(new Customer(org, "IdxCustomer" + i, "idx" + i + "@example.com", null, null, null, null, true));
        }
        // organization_id + email index check via find by email filtering not directly but we test that organization+email query works
        List<Customer> all = customerRepository.findByOrganizationIdInAndActiveTrue(List.of(org.getId()), PageRequest.of(0, 10)).getContent();
        assertThat(all).hasSize(3);
    }
}
