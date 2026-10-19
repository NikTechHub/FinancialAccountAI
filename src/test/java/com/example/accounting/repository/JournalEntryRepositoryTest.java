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
class JournalEntryRepositoryTest {

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    private Organization createOrg(String suffix) {
        return organizationRepository.save(new Organization("Org-" + suffix, "Legal-" + suffix, "LLP", "India", "INR", "Asia/Kolkata"));
    }

    private User createUser(String suffix) {
        return userRepository.save(new User("First" + suffix, "Last" + suffix, "user" + suffix + "@example.com", "hash", true));
    }

    private Account createAccount(Organization org, String code) {
        return accountRepository.save(new Account(org, code, "Account " + code, AccountType.ASSET, null, null, true, false));
    }

    private JournalEntry createJournalEntry(Organization org, User user, String entryNumber, LocalDate date, JournalEntryStatus status) {
        JournalEntry entry = new JournalEntry(org, entryNumber, date, "desc", "ref", user);
        entry.setStatus(status);
        Account acc1 = createAccount(org, "1000-" + System.nanoTime() % 10000);
        Account acc2 = createAccount(org, "4000-" + System.nanoTime() % 10000);
        // need fresh accounts with unique codes
        JournalEntryLine line1 = new JournalEntryLine(entry, acc1, null, new BigDecimal("100.00"), BigDecimal.ZERO);
        JournalEntryLine line2 = new JournalEntryLine(entry, acc2, null, BigDecimal.ZERO, new BigDecimal("100.00"));
        entry.addLine(line1);
        entry.addLine(line2);
        return journalEntryRepository.save(entry);
    }

    @Test
    void shouldCreateAndFindJournalEntry() {
        Organization org = createOrg("create");
        User user = createUser("create");
        JournalEntry entry = createJournalEntry(org, user, "JE-001", LocalDate.now(), JournalEntryStatus.DRAFT);
        assertThat(entry.getId()).isNotNull();
        assertThat(journalEntryRepository.findByIdAndOrganizationId(entry.getId(), org.getId())).isPresent();
    }

    @Test
    void shouldEnforceUniqueEntryNumberPerOrganization() {
        Organization org = createOrg("uniq");
        User user = createUser("uniq");
        createJournalEntry(org, user, "JE-UNIQ", LocalDate.now(), JournalEntryStatus.DRAFT);
        journalEntryRepository.flush();
        JournalEntry duplicate = new JournalEntry(org, "JE-UNIQ", LocalDate.now(), "desc2", "ref2", user);
        duplicate.setStatus(JournalEntryStatus.DRAFT);
        Account acc = createAccount(org, "2000");
        duplicate.addLine(new JournalEntryLine(duplicate, acc, null, new BigDecimal("10"), BigDecimal.ZERO));
        duplicate.addLine(new JournalEntryLine(duplicate, createAccount(org, "3000"), null, BigDecimal.ZERO, new BigDecimal("10")));
        try {
            journalEntryRepository.saveAndFlush(duplicate);
            assertThat(false).as("Expected unique violation").isTrue();
        } catch (Exception ex) {
            assertThat(ex).isNotNull();
        }
    }

    @Test
    void shouldAllowSameEntryNumberInDifferentOrganizations() {
        Organization org1 = createOrg("org1");
        Organization org2 = createOrg("org2");
        User user1 = createUser("u1");
        User user2 = createUser("u2");
        JournalEntry e1 = createJournalEntry(org1, user1, "JE-SHARED", LocalDate.now(), JournalEntryStatus.DRAFT);
        JournalEntry e2 = createJournalEntry(org2, user2, "JE-SHARED", LocalDate.now(), JournalEntryStatus.DRAFT);
        assertThat(e1.getId()).isNotEqualTo(e2.getId());
    }

    @Test
    void shouldHandleJournalLines() {
        Organization org = createOrg("lines");
        User user = createUser("lines");
        JournalEntry entry = new JournalEntry(org, "JE-LINES", LocalDate.now(), "desc", null, user);
        entry.setStatus(JournalEntryStatus.DRAFT);
        Account acc1 = createAccount(org, "1100");
        Account acc2 = createAccount(org, "1200");
        entry.addLine(new JournalEntryLine(entry, acc1, "debit", new BigDecimal("500"), BigDecimal.ZERO));
        entry.addLine(new JournalEntryLine(entry, acc2, "credit", BigDecimal.ZERO, new BigDecimal("500")));
        JournalEntry saved = journalEntryRepository.save(entry);
        assertThat(saved.getLines()).hasSize(2);
    }

    @Test
    void shouldIsolateOrganization() {
        Organization org1 = createOrg("iso1");
        Organization org2 = createOrg("iso2");
        User user1 = createUser("iso1");
        createJournalEntry(org1, user1, "JE-ISO1", LocalDate.now(), JournalEntryStatus.DRAFT);
        List<JournalEntry> org1Entries = journalEntryRepository.findByOrganizationId(org1.getId());
        assertThat(org1Entries).hasSize(1);
        assertThat(journalEntryRepository.findByOrganizationId(org2.getId())).isEmpty();
        assertThat(journalEntryRepository.findByIdAndOrganizationId(org1Entries.get(0).getId(), org2.getId())).isNotPresent();
    }

    @Test
    void shouldFilterByStatus() {
        Organization org = createOrg("status");
        User user = createUser("status");
        createJournalEntry(org, user, "JE-DRAFT", LocalDate.now(), JournalEntryStatus.DRAFT);
        createJournalEntry(org, user, "JE-POSTED", LocalDate.now(), JournalEntryStatus.POSTED);
        Page<JournalEntry> drafts = journalEntryRepository.findByOrganizationIdInAndStatus(List.of(org.getId()), JournalEntryStatus.DRAFT, PageRequest.of(0, 10));
        assertThat(drafts.getTotalElements()).isEqualTo(1);
        Page<JournalEntry> posted = journalEntryRepository.findByOrganizationIdInAndStatus(List.of(org.getId()), JournalEntryStatus.POSTED, PageRequest.of(0, 10));
        assertThat(posted.getTotalElements()).isEqualTo(1);
    }

    @Test
    void shouldFilterByDate() {
        Organization org = createOrg("date");
        User user = createUser("date");
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        createJournalEntry(org, user, "JE-TODAY", today, JournalEntryStatus.DRAFT);
        createJournalEntry(org, user, "JE-YESTERDAY", yesterday, JournalEntryStatus.DRAFT);
        Page<JournalEntry> todayPage = journalEntryRepository.findByOrganizationIdInAndEntryDate(List.of(org.getId()), today, PageRequest.of(0, 10));
        assertThat(todayPage.getTotalElements()).isEqualTo(1);
    }

    @Test
    void shouldSearchByEntryNumberOrReference() {
        Organization org = createOrg("search");
        User user = createUser("search");
        JournalEntry e1 = new JournalEntry(org, "JE-SEARCH-001", LocalDate.now(), "desc", "REF-001", user);
        e1.setStatus(JournalEntryStatus.DRAFT);
        e1.addLine(new JournalEntryLine(e1, createAccount(org, "1001"), null, new BigDecimal("10"), BigDecimal.ZERO));
        e1.addLine(new JournalEntryLine(e1, createAccount(org, "4001"), null, BigDecimal.ZERO, new BigDecimal("10")));
        journalEntryRepository.save(e1);

        JournalEntry e2 = new JournalEntry(org, "JE-SEARCH-002", LocalDate.now(), "desc", "REF-002", user);
        e2.setStatus(JournalEntryStatus.DRAFT);
        e2.addLine(new JournalEntryLine(e2, createAccount(org, "1002"), null, new BigDecimal("10"), BigDecimal.ZERO));
        e2.addLine(new JournalEntryLine(e2, createAccount(org, "4002"), null, BigDecimal.ZERO, new BigDecimal("10")));
        journalEntryRepository.save(e2);

        Page<JournalEntry> byNumber = journalEntryRepository.searchByEntryNumberOrReference(List.of(org.getId()), "SEARCH-001", PageRequest.of(0, 10));
        assertThat(byNumber.getTotalElements()).isEqualTo(1);

        Page<JournalEntry> byRef = journalEntryRepository.searchByEntryNumberOrReference(List.of(org.getId()), "REF-002", PageRequest.of(0, 10));
        assertThat(byRef.getTotalElements()).isEqualTo(1);
    }

    @Test
    void shouldPaginate() {
        Organization org = createOrg("page");
        User user = createUser("page");
        for (int i = 0; i < 5; i++) {
            createJournalEntry(org, user, "JE-PAGE-" + i, LocalDate.now(), JournalEntryStatus.DRAFT);
        }
        Page<JournalEntry> p1 = journalEntryRepository.findByOrganizationIdIn(List.of(org.getId()), PageRequest.of(0, 2));
        assertThat(p1.getTotalElements()).isEqualTo(5);
        assertThat(p1.getContent()).hasSize(2);
        Page<JournalEntry> p2 = journalEntryRepository.findByOrganizationIdIn(List.of(org.getId()), PageRequest.of(1, 2));
        assertThat(p2.getContent()).hasSize(2);
    }
}
