package com.example.accounting.repository;

import com.example.accounting.config.JpaAuditingConfig;
import com.example.accounting.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
class ExpenseRepositoryTest {

    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private VendorRepository vendorRepository;
    @Autowired
    private ExpenseRepository expenseRepository;

    private Organization createOrg(String suffix) {
        return organizationRepository.save(new Organization("Org-" + suffix, "Legal-" + suffix, "LLP", "India", "INR", "Asia/Kolkata"));
    }

    private User createUser(String suffix) {
        return userRepository.save(new User("First" + suffix, "Last" + suffix, "user" + suffix + "@example.com", "hash", true));
    }

    private Account createAccount(Organization org, String code) {
        return accountRepository.save(new Account(org, code, "Account " + code, AccountType.EXPENSE, null, null, true, false));
    }

    private Vendor createVendor(Organization org, String name) {
        return vendorRepository.save(new Vendor(org, name, "v@v.com", null, null, null, null, true));
    }

    private Expense createExpense(Organization org, User user, Account expAcc, Account payAcc, Vendor vendor, ExpenseStatus status) {
        Expense e = new Expense(org, vendor, expAcc, payAcc, new BigDecimal("100.00"), LocalDate.now(), "desc", "REF-" + System.nanoTime(), user);
        e.setStatus(status);
        return expenseRepository.save(e);
    }

    @Test
    void shouldCreateAndFindExpense() {
        Organization org = createOrg("create");
        User user = createUser("create");
        Account expAcc = createAccount(org, "5000");
        Account payAcc = createAccount(org, "1000");
        Expense e = createExpense(org, user, expAcc, payAcc, null, ExpenseStatus.DRAFT);
        assertThat(e.getId()).isNotNull();
        assertThat(expenseRepository.findByIdAndOrganizationId(e.getId(), org.getId())).isPresent();
        assertThat(e.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldIsolateOrganization() {
        Organization org1 = createOrg("iso1");
        Organization org2 = createOrg("iso2");
        User user1 = createUser("iso1");
        Account ea1 = createAccount(org1, "5001");
        Account pa1 = createAccount(org1, "1001");
        Expense e1 = createExpense(org1, user1, ea1, pa1, null, ExpenseStatus.DRAFT);
        assertThat(expenseRepository.findByIdAndOrganizationId(e1.getId(), org2.getId())).isNotPresent();
        assertThat(expenseRepository.findByOrganizationId(org1.getId())).hasSize(1);
        assertThat(expenseRepository.findByOrganizationId(org2.getId())).isEmpty();
    }

    @Test
    void shouldFilterByStatus() {
        Organization org = createOrg("status");
        User user = createUser("status");
        Account ea = createAccount(org, "5000");
        Account pa = createAccount(org, "1000");
        createExpense(org, user, ea, pa, null, ExpenseStatus.DRAFT);
        createExpense(org, user, ea, pa, null, ExpenseStatus.RECORDED);
        Page<Expense> drafts = expenseRepository.findByOrganizationIdInAndStatus(List.of(org.getId()), ExpenseStatus.DRAFT, PageRequest.of(0, 10));
        assertThat(drafts.getTotalElements()).isEqualTo(1);
        Page<Expense> recorded = expenseRepository.findByOrganizationIdInAndStatus(List.of(org.getId()), ExpenseStatus.RECORDED, PageRequest.of(0, 10));
        assertThat(recorded.getTotalElements()).isEqualTo(1);
    }

    @Test
    void shouldFilterByVendor() {
        Organization org = createOrg("vendor");
        User user = createUser("vendor");
        Account ea = createAccount(org, "5000");
        Account pa = createAccount(org, "1000");
        Vendor v1 = createVendor(org, "Vendor1");
        Vendor v2 = createVendor(org, "Vendor2");
        createExpense(org, user, ea, pa, v1, ExpenseStatus.DRAFT);
        createExpense(org, user, ea, pa, v2, ExpenseStatus.DRAFT);
        Page<Expense> byVendor = expenseRepository.findByOrganizationIdInAndVendorId(List.of(org.getId()), v1.getId(), PageRequest.of(0, 10));
        assertThat(byVendor.getTotalElements()).isEqualTo(1);
    }

    @Test
    void shouldFilterByExpenseAccount() {
        Organization org = createOrg("acc");
        User user = createUser("acc");
        Account ea1 = createAccount(org, "5001");
        Account ea2 = createAccount(org, "5002");
        Account pa = createAccount(org, "1000");
        createExpense(org, user, ea1, pa, null, ExpenseStatus.DRAFT);
        createExpense(org, user, ea2, pa, null, ExpenseStatus.DRAFT);
        Page<Expense> byAcc = expenseRepository.findByOrganizationIdInAndExpenseAccountId(List.of(org.getId()), ea1.getId(), PageRequest.of(0, 10));
        assertThat(byAcc.getTotalElements()).isEqualTo(1);
    }

    @Test
    void shouldFilterByDate() {
        Organization org = createOrg("date");
        User user = createUser("date");
        Account ea = createAccount(org, "5000");
        Account pa = createAccount(org, "1000");
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        Expense e1 = new Expense(org, null, ea, pa, new BigDecimal("10"), today, "desc", "ref1", user);
        e1.setStatus(ExpenseStatus.DRAFT);
        Expense e2 = new Expense(org, null, ea, pa, new BigDecimal("10"), yesterday, "desc", "ref2", user);
        e2.setStatus(ExpenseStatus.DRAFT);
        expenseRepository.save(e1);
        expenseRepository.save(e2);
        Page<Expense> todayPage = expenseRepository.findByOrganizationIdInAndExpenseDate(List.of(org.getId()), today, PageRequest.of(0, 10));
        assertThat(todayPage.getTotalElements()).isEqualTo(1);
    }

    @Test
    void shouldSearchByReference() {
        Organization org = createOrg("search");
        User user = createUser("search");
        Account ea = createAccount(org, "5000");
        Account pa = createAccount(org, "1000");
        Expense e1 = new Expense(org, null, ea, pa, new BigDecimal("10"), LocalDate.now(), "desc", "REF-SEARCH-001", user);
        e1.setStatus(ExpenseStatus.DRAFT);
        Expense e2 = new Expense(org, null, ea, pa, new BigDecimal("10"), LocalDate.now(), "desc", "REF-OTHER", user);
        e2.setStatus(ExpenseStatus.DRAFT);
        expenseRepository.save(e1);
        expenseRepository.save(e2);
        Page<Expense> byRef = expenseRepository.searchByReferenceOrDescription(List.of(org.getId()), "SEARCH-001", PageRequest.of(0, 10));
        assertThat(byRef.getTotalElements()).isEqualTo(1);
    }

    @Test
    void shouldPaginate() {
        Organization org = createOrg("page");
        User user = createUser("page");
        Account ea = createAccount(org, "5000");
        Account pa = createAccount(org, "1000");
        for (int i = 0; i < 5; i++) {
            createExpense(org, user, ea, pa, null, ExpenseStatus.DRAFT);
        }
        Page<Expense> p1 = expenseRepository.findByOrganizationIdIn(List.of(org.getId()), PageRequest.of(0, 2));
        assertThat(p1.getTotalElements()).isEqualTo(5);
        assertThat(p1.getContent()).hasSize(2);
        Page<Expense> p2 = expenseRepository.findByOrganizationIdIn(List.of(org.getId()), PageRequest.of(1, 2));
        assertThat(p2.getContent()).hasSize(2);
    }

    @Test
    void shouldHandleJournalEntryRelationship() {
        Organization org = createOrg("journal");
        User user = createUser("journal");
        Account ea = createAccount(org, "5000");
        Account pa = createAccount(org, "1000");
        Expense e = createExpense(org, user, ea, pa, null, ExpenseStatus.DRAFT);
        assertThat(e.getJournalEntry()).isNull();
        // simulate linking
        // we don't create journal entry here, just check nullable
        assertThat(e.getJournalEntry()).isNull();
    }

    @Test
    void shouldNotRetrieveOtherOrganizationExpense() {
        Organization org1 = createOrg("other1");
        Organization org2 = createOrg("other2");
        User user1 = createUser("other1");
        Account ea1 = createAccount(org1, "5000");
        Account pa1 = createAccount(org1, "1000");
        Expense e1 = createExpense(org1, user1, ea1, pa1, null, ExpenseStatus.DRAFT);
        assertThat(expenseRepository.findByIdAndOrganizationId(e1.getId(), org2.getId())).isNotPresent();
    }
}
