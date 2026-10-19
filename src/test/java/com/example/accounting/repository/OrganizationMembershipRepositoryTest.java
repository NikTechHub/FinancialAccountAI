package com.example.accounting.repository;

import com.example.accounting.config.JpaAuditingConfig;
import com.example.accounting.entity.MembershipRole;
import com.example.accounting.entity.Organization;
import com.example.accounting.entity.OrganizationMembership;
import com.example.accounting.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
class OrganizationMembershipRepositoryTest {

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Test
    void shouldCreateMembershipAndPreventDuplicate() {
        Organization org = organizationRepository.save(new Organization("Mem Org", "Legal Mem", "LLP", "India", "INR", "Asia/Kolkata"));
        User user = userRepository.save(new User("Mem", "User", "mem@example.com", "hash", true));

        OrganizationMembership m1 = membershipRepository.save(new OrganizationMembership(user, org, MembershipRole.OWNER));
        assertThat(m1.getId()).isNotNull();
        assertThat(m1.getCreatedAt()).isNotNull();

        assertThat(membershipRepository.existsByUserIdAndOrganizationId(user.getId(), org.getId())).isTrue();
        assertThat(membershipRepository.findByOrganizationId(org.getId())).hasSize(1);
        assertThat(membershipRepository.findByUserId(user.getId())).hasSize(1);

        // duplicate should violate unique constraint
        OrganizationMembership duplicate = new OrganizationMembership(user, org, MembershipRole.ADMIN);
        try {
            membershipRepository.saveAndFlush(duplicate);
            assertThat(false).as("Expected duplicate membership violation").isTrue();
        } catch (Exception ex) {
            assertThat(ex).isNotNull();
        }
    }

    @Test
    void shouldAllowUserMultipleOrganizations() {
        Organization org1 = organizationRepository.save(new Organization("Org1", "Legal1", "LLP", "India", "INR", "Asia/Kolkata"));
        Organization org2 = organizationRepository.save(new Organization("Org2", "Legal2", "LLP", "USA", "USD", "America/New_York"));
        User user = userRepository.save(new User("Multi", "OrgUser", "multi@example.com", "hash", true));

        membershipRepository.save(new OrganizationMembership(user, org1, MembershipRole.ADMIN));
        membershipRepository.save(new OrganizationMembership(user, org2, MembershipRole.VIEWER));

        assertThat(membershipRepository.findByUserId(user.getId())).hasSize(2);
    }

    @Test
    void shouldListMembersByOrganization() {
        Organization org = organizationRepository.save(new Organization("List Org", "Legal List", "CORP", "UK", "GBP", "Europe/London"));
        User u1 = userRepository.save(new User("List1", "User1", "list1@example.com", "hash", true));
        User u2 = userRepository.save(new User("List2", "User2", "list2@example.com", "hash", true));

        membershipRepository.save(new OrganizationMembership(u1, org, MembershipRole.ACCOUNTANT));
        membershipRepository.save(new OrganizationMembership(u2, org, MembershipRole.EMPLOYEE));

        List<OrganizationMembership> members = membershipRepository.findByOrganizationId(org.getId());
        assertThat(members).hasSize(2);
    }
}
