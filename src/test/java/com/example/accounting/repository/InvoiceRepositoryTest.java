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
class InvoiceRepositoryTest {

    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private InvoiceRepository invoiceRepository;

    private Organization createOrg(String suffix) {
        return organizationRepository.save(new Organization("Org-" + suffix, "Legal-" + suffix, "LLP", "India", "INR", "Asia/Kolkata"));
    }
    private User createUser(String suffix) {
        return userRepository.save(new User("First" + suffix, "Last" + suffix, "user" + suffix + "@example.com", "hash", true));
    }
    private Customer createCustomer(Organization org, String suffix) {
        return customerRepository.save(new Customer(org, "Cust-" + suffix, "c" + suffix + "@example.com", null, null, null, null, true));
    }
    private Account createAccount(Organization org, String code, AccountType type) {
        return accountRepository.save(new Account(org, code, "Acc " + code, type, null, null, true, false));
    }

    private Invoice createInvoice(Organization org, Customer cust, User user, String number, InvoiceStatus status) {
        Invoice inv = new Invoice();
        inv.setOrganization(org);
        inv.setCustomer(cust);
        inv.setInvoiceNumber(number);
        inv.setInvoiceDate(LocalDate.now());
        inv.setDueDate(LocalDate.now().plusDays(10));
        inv.setStatus(status);
        inv.setCreatedBy(user);
        inv.setSubtotal(new BigDecimal("100.00"));
        inv.setTaxAmount(new BigDecimal("10.00"));
        inv.setDiscountAmount(new BigDecimal("5.00"));
        inv.setTotalAmount(new BigDecimal("105.00"));
        inv.setAmountPaid(BigDecimal.ZERO);
        inv.setBalanceDue(new BigDecimal("105.00"));
        // add one line minimal
        Account revAcc = createAccount(org, "4000-" + System.nanoTime() % 10000, AccountType.REVENUE);
        InvoiceLine line = new InvoiceLine(inv, "Desc", new BigDecimal("1"), new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("5"), revAcc, new BigDecimal("100"), new BigDecimal("9.50"), new BigDecimal("104.50"));
        inv.addLine(line);
        return invoiceRepository.save(inv);
    }

    @Test
    void shouldCreateAndFindInvoice() {
        Organization org = createOrg("create");
        Customer cust = createCustomer(org, "create");
        User user = createUser("create");
        Invoice inv = createInvoice(org, cust, user, "INV-001", InvoiceStatus.DRAFT);
        assertThat(inv.getId()).isNotNull();
        assertThat(invoiceRepository.findByIdAndOrganizationId(inv.getId(), org.getId())).isPresent();
    }

    @Test
    void shouldEnforceUniqueInvoiceNumberPerOrganization() {
        Organization org = createOrg("uniq");
        Customer cust = createCustomer(org, "uniq");
        User user = createUser("uniq");
        createInvoice(org, cust, user, "INV-UNIQ", InvoiceStatus.DRAFT);
        invoiceRepository.flush();
        Invoice dup = new Invoice();
        dup.setOrganization(org);
        dup.setCustomer(cust);
        dup.setInvoiceNumber("INV-UNIQ");
        dup.setInvoiceDate(LocalDate.now());
        dup.setStatus(InvoiceStatus.DRAFT);
        dup.setCreatedBy(user);
        dup.setSubtotal(BigDecimal.ZERO);
        dup.setTaxAmount(BigDecimal.ZERO);
        dup.setDiscountAmount(BigDecimal.ZERO);
        dup.setTotalAmount(BigDecimal.ZERO);
        dup.setAmountPaid(BigDecimal.ZERO);
        dup.setBalanceDue(BigDecimal.ZERO);
        try {
            invoiceRepository.saveAndFlush(dup);
            assertThat(false).as("Expected duplicate violation").isTrue();
        } catch (Exception ex) {
            assertThat(ex).isNotNull();
        }
    }

    @Test
    void shouldAllowSameNumberInDifferentOrgs() {
        Organization org1 = createOrg("org1");
        Organization org2 = createOrg("org2");
        Customer c1 = createCustomer(org1, "c1");
        Customer c2 = createCustomer(org2, "c2");
        User u1 = createUser("u1");
        User u2 = createUser("u2");
        Invoice i1 = createInvoice(org1, c1, u1, "INV-SHARED", InvoiceStatus.DRAFT);
        Invoice i2 = createInvoice(org2, c2, u2, "INV-SHARED", InvoiceStatus.DRAFT);
        assertThat(i1.getId()).isNotEqualTo(i2.getId());
    }

    @Test
    void shouldHandleInvoiceLines() {
        Organization org = createOrg("lines");
        Customer cust = createCustomer(org, "lines");
        User user = createUser("lines");
        Invoice inv = new Invoice();
        inv.setOrganization(org);
        inv.setCustomer(cust);
        inv.setInvoiceNumber("INV-LINES");
        inv.setInvoiceDate(LocalDate.now());
        inv.setStatus(InvoiceStatus.DRAFT);
        inv.setCreatedBy(user);
        inv.setSubtotal(BigDecimal.ZERO);
        inv.setTaxAmount(BigDecimal.ZERO);
        inv.setDiscountAmount(BigDecimal.ZERO);
        inv.setTotalAmount(BigDecimal.ZERO);
        inv.setAmountPaid(BigDecimal.ZERO);
        inv.setBalanceDue(BigDecimal.ZERO);
        Account rev1 = createAccount(org, "4001", AccountType.REVENUE);
        Account rev2 = createAccount(org, "4002", AccountType.REVENUE);
        inv.addLine(new InvoiceLine(inv, "Line1", new BigDecimal("2"), new BigDecimal("50"), new BigDecimal("10"), BigDecimal.ZERO, rev1, new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("110")));
        inv.addLine(new InvoiceLine(inv, "Line2", new BigDecimal("1"), new BigDecimal("100"), new BigDecimal("5"), new BigDecimal("5"), rev2, new BigDecimal("100"), new BigDecimal("4.75"), new BigDecimal("99.75")));
        Invoice saved = invoiceRepository.save(inv);
        assertThat(saved.getLines()).hasSize(2);
    }

    @Test
    void shouldIsolateOrganization() {
        Organization org1 = createOrg("iso1");
        Organization org2 = createOrg("iso2");
        Customer c1 = createCustomer(org1, "c1");
        User u1 = createUser("iso1");
        Invoice inv = createInvoice(org1, c1, u1, "INV-ISO", InvoiceStatus.DRAFT);
        assertThat(invoiceRepository.findByIdAndOrganizationId(inv.getId(), org2.getId())).isNotPresent();
        assertThat(invoiceRepository.findByOrganizationId(org1.getId())).hasSize(1);
        assertThat(invoiceRepository.findByOrganizationId(org2.getId())).isEmpty();
    }

    @Test
    void shouldFilterByStatus() {
        Organization org = createOrg("status");
        Customer cust = createCustomer(org, "status");
        User user = createUser("status");
        createInvoice(org, cust, user, "INV-DRAFT", InvoiceStatus.DRAFT);
        createInvoice(org, cust, user, "INV-ISSUED", InvoiceStatus.ISSUED);
        Page<Invoice> drafts = invoiceRepository.findByOrganizationIdInAndStatus(List.of(org.getId()), InvoiceStatus.DRAFT, PageRequest.of(0, 10));
        assertThat(drafts.getTotalElements()).isEqualTo(1);
    }

    @Test
    void shouldFilterByCustomer() {
        Organization org = createOrg("custfilter");
        Customer c1 = createCustomer(org, "c1");
        Customer c2 = createCustomer(org, "c2");
        User user = createUser("custfilter");
        createInvoice(org, c1, user, "INV-C1", InvoiceStatus.DRAFT);
        createInvoice(org, c2, user, "INV-C2", InvoiceStatus.DRAFT);
        Page<Invoice> byCust = invoiceRepository.findByOrganizationIdInAndCustomerId(List.of(org.getId()), c1.getId(), PageRequest.of(0, 10));
        assertThat(byCust.getTotalElements()).isEqualTo(1);
    }

    @Test
    void shouldFilterByDate() {
        Organization org = createOrg("date");
        Customer cust = createCustomer(org, "date");
        User user = createUser("date");
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        Invoice inv1 = new Invoice();
        inv1.setOrganization(org);
        inv1.setCustomer(cust);
        inv1.setInvoiceNumber("INV-TODAY");
        inv1.setInvoiceDate(today);
        inv1.setStatus(InvoiceStatus.DRAFT);
        inv1.setCreatedBy(user);
        inv1.setSubtotal(BigDecimal.ZERO);
        inv1.setTaxAmount(BigDecimal.ZERO);
        inv1.setDiscountAmount(BigDecimal.ZERO);
        inv1.setTotalAmount(BigDecimal.ZERO);
        inv1.setAmountPaid(BigDecimal.ZERO);
        inv1.setBalanceDue(BigDecimal.ZERO);
        inv1.addLine(new InvoiceLine(inv1, "desc", BigDecimal.ONE, new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, createAccount(org, "4003", AccountType.REVENUE), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN));
        invoiceRepository.save(inv1);
        Invoice inv2 = new Invoice();
        inv2.setOrganization(org);
        inv2.setCustomer(cust);
        inv2.setInvoiceNumber("INV-YEST");
        inv2.setInvoiceDate(yesterday);
        inv2.setStatus(InvoiceStatus.DRAFT);
        inv2.setCreatedBy(user);
        inv2.setSubtotal(BigDecimal.ZERO);
        inv2.setTaxAmount(BigDecimal.ZERO);
        inv2.setDiscountAmount(BigDecimal.ZERO);
        inv2.setTotalAmount(BigDecimal.ZERO);
        inv2.setAmountPaid(BigDecimal.ZERO);
        inv2.setBalanceDue(BigDecimal.ZERO);
        inv2.addLine(new InvoiceLine(inv2, "desc", BigDecimal.ONE, new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, createAccount(org, "4004", AccountType.REVENUE), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN));
        invoiceRepository.save(inv2);
        Page<Invoice> todayPage = invoiceRepository.findByOrganizationIdInAndInvoiceDate(List.of(org.getId()), today, PageRequest.of(0, 10));
        assertThat(todayPage.getTotalElements()).isEqualTo(1);
    }

    @Test
    void shouldPaginate() {
        Organization org = createOrg("page");
        Customer cust = createCustomer(org, "page");
        User user = createUser("page");
        for (int i = 0; i < 5; i++) {
            createInvoice(org, cust, user, "INV-PAGE-" + i, InvoiceStatus.DRAFT);
        }
        Page<Invoice> p1 = invoiceRepository.findByOrganizationIdIn(List.of(org.getId()), PageRequest.of(0, 2));
        assertThat(p1.getTotalElements()).isEqualTo(5);
        assertThat(p1.getContent()).hasSize(2);
    }
}
