package com.example.accounting.repository;

import com.example.accounting.config.JpaAuditingConfig;
import com.example.accounting.entity.Account;
import com.example.accounting.entity.AccountType;
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
class AccountRepositoryTest {

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void shouldSaveAndFindAccount() {
        Organization org = organizationRepository.save(new Organization("Acc Org", "Legal", "LLP", "India", "INR", "Asia/Kolkata"));
        Account acc = new Account(org, "1000", "Cash", AccountType.ASSET, null, "Cash account", true, false);
        Account saved = accountRepository.save(acc);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(accountRepository.findByIdAndOrganizationId(saved.getId(), org.getId())).isPresent();
    }

    @Test
    void shouldEnforceUniqueCodeWithinOrganization() {
        Organization org = organizationRepository.save(new Organization("Uniq Org", "Legal", "LLP", "India", "INR", "Asia/Kolkata"));
        accountRepository.save(new Account(org, "1000", "Cash", AccountType.ASSET, null, null, true, false));
        accountRepository.flush();
        Account duplicate = new Account(org, "1000", "Duplicate", AccountType.LIABILITY, null, null, true, false);
        try {
            accountRepository.saveAndFlush(duplicate);
            assertThat(false).as("Expected duplicate code violation").isTrue();
        } catch (Exception ex) {
            assertThat(ex).isNotNull();
        }
    }

    @Test
    void shouldAllowSameCodeInDifferentOrganizations() {
        Organization org1 = organizationRepository.save(new Organization("Org1", "Legal1", "LLP", "India", "INR", "Asia/Kolkata"));
        Organization org2 = organizationRepository.save(new Organization("Org2", "Legal2", "LLP", "USA", "USD", "America/New_York"));
        accountRepository.save(new Account(org1, "1000", "Cash", AccountType.ASSET, null, null, true, false));
        Account a2 = accountRepository.save(new Account(org2, "1000", "Cash Other", AccountType.ASSET, null, null, true, false));
        assertThat(a2.getId()).isNotNull();
    }

    @Test
    void shouldHandleParentChildRelationship() {
        Organization org = organizationRepository.save(new Organization("Parent Org", "Legal", "LLP", "India", "INR", "Asia/Kolkata"));
        Account parent = accountRepository.save(new Account(org, "5000", "Operating Expenses", AccountType.EXPENSE, null, null, true, false));
        Account child = accountRepository.save(new Account(org, "5100", "Office Expenses", AccountType.EXPENSE, parent, null, true, false));
        assertThat(child.getParentAccount().getId()).isEqualTo(parent.getId());
        List<Account> children = accountRepository.findByParentAccountIdAndActiveTrue(parent.getId());
        assertThat(children).hasSize(1);
    }

    @Test
    void shouldSearchByNameOrCode() {
        Organization org = organizationRepository.save(new Organization("Search Org", "Legal", "LLP", "India", "INR", "Asia/Kolkata"));
        accountRepository.save(new Account(org, "1000", "Cash", AccountType.ASSET, null, null, true, false));
        accountRepository.save(new Account(org, "1100", "Accounts Receivable", AccountType.ASSET, null, null, true, false));
        accountRepository.save(new Account(org, "2000", "Accounts Payable", AccountType.LIABILITY, null, null, true, false));

        Page<Account> page = accountRepository.searchByNameOrCode(List.of(org.getId()), "cash", PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        Page<Account> page2 = accountRepository.searchByNameOrCode(List.of(org.getId()), "1100", PageRequest.of(0, 10));
        assertThat(page2.getTotalElements()).isEqualTo(1);
    }

    @Test
    void shouldPaginate() {
        Organization org = organizationRepository.save(new Organization("Page Org", "Legal", "LLP", "India", "INR", "Asia/Kolkata"));
        for (int i = 0; i < 5; i++) {
            accountRepository.save(new Account(org, "C" + i, "Account" + i, AccountType.ASSET, null, null, true, false));
        }
        Page<Account> p1 = accountRepository.findByOrganizationIdInAndActiveTrue(List.of(org.getId()), PageRequest.of(0, 2));
        assertThat(p1.getTotalElements()).isEqualTo(5);
        assertThat(p1.getContent()).hasSize(2);
        Page<Account> p2 = accountRepository.findByOrganizationIdInAndActiveTrue(List.of(org.getId()), PageRequest.of(1, 2));
        assertThat(p2.getContent()).hasSize(2);
    }

    @Test
    void shouldFilterByType() {
        Organization org = organizationRepository.save(new Organization("Type Org", "Legal", "LLP", "India", "INR", "Asia/Kolkata"));
        accountRepository.save(new Account(org, "1000", "Cash", AccountType.ASSET, null, null, true, false));
        accountRepository.save(new Account(org, "4000", "Sales", AccountType.REVENUE, null, null, true, false));
        accountRepository.save(new Account(org, "5000", "Expense", AccountType.EXPENSE, null, null, true, false));

        Page<Account> assets = accountRepository.findByOrganizationIdInAndTypeAndActiveTrue(List.of(org.getId()), AccountType.ASSET, PageRequest.of(0, 10));
        assertThat(assets.getTotalElements()).isEqualTo(1);
    }

    @Test
    void shouldFilterActiveOnly() {
        Organization org = organizationRepository.save(new Organization("Active Org", "Legal", "LLP", "India", "INR", "Asia/Kolkata"));
        accountRepository.save(new Account(org, "1000", "Cash", AccountType.ASSET, null, null, true, false));
        Account inactive = new Account(org, "2000", "Payable", AccountType.LIABILITY, null, null, false, false);
        accountRepository.save(inactive);

        List<Account> active = accountRepository.findByOrganizationIdAndActiveTrue(org.getId());
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getCode()).isEqualTo("1000");
    }

    @Test
    void shouldIsolateOrganization() {
        Organization org1 = organizationRepository.save(new Organization("Iso1", "Legal1", "LLP", "India", "INR", "Asia/Kolkata"));
        Organization org2 = organizationRepository.save(new Organization("Iso2", "Legal2", "LLP", "USA", "USD", "America/New_York"));
        accountRepository.save(new Account(org1, "1000", "Cash1", AccountType.ASSET, null, null, true, false));
        accountRepository.save(new Account(org2, "1000", "Cash2", AccountType.ASSET, null, null, true, false));

        List<Account> org1Accounts = accountRepository.findByOrganizationId(org1.getId());
        assertThat(org1Accounts).hasSize(1);
        assertThat(org1Accounts.get(0).getName()).isEqualTo("Cash1");

        assertThat(accountRepository.findByIdAndOrganizationId(org1Accounts.get(0).getId(), org2.getId())).isNotPresent();
    }

    @Test
    void shouldHandleParentFiltering() {
        Organization org = organizationRepository.save(new Organization("ParentFilter Org", "Legal", "LLP", "India", "INR", "Asia/Kolkata"));
        Account parent = accountRepository.save(new Account(org, "5000", "Parent", AccountType.EXPENSE, null, null, true, false));
        accountRepository.save(new Account(org, "5100", "Child1", AccountType.EXPENSE, parent, null, true, false));
        accountRepository.save(new Account(org, "5200", "Child2", AccountType.EXPENSE, parent, null, true, false));
        accountRepository.save(new Account(org, "1000", "Cash", AccountType.ASSET, null, null, true, false));

        Page<Account> children = accountRepository.findByOrganizationIdInAndParentAccountIdAndActiveTrue(List.of(org.getId()), parent.getId(), PageRequest.of(0, 10));
        assertThat(children.getTotalElements()).isEqualTo(2);
    }
}
