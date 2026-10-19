package com.example.accounting.service;

import com.example.accounting.audit.AuditAction;
import com.example.accounting.audit.AuditCommand;
import com.example.accounting.audit.AuditLogService;
import com.example.accounting.audit.AuditResourceType;
import com.example.accounting.audit.AuditSnapshots;
import com.example.accounting.dto.*;
import com.example.accounting.entity.*;
import com.example.accounting.exception.*;
import com.example.accounting.repository.AccountRepository;
import com.example.accounting.repository.JournalEntryRepository;
import com.example.accounting.repository.OrganizationMembershipRepository;
import com.example.accounting.repository.OrganizationRepository;
import com.example.accounting.repository.UserRepository;
import com.example.accounting.security.OrganizationAccessService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class JournalEntryService {

    private final JournalEntryRepository journalEntryRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationAccessService organizationAccessService;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public JournalEntryService(JournalEntryRepository journalEntryRepository,
                               OrganizationRepository organizationRepository,
                               OrganizationMembershipRepository membershipRepository,
                               OrganizationAccessService organizationAccessService,
                               AccountRepository accountRepository,
                               UserRepository userRepository,
                               AuditLogService auditLogService) {
        this.journalEntryRepository = journalEntryRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.organizationAccessService = organizationAccessService;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    private List<UUID> accessibleOrganizationIds(UUID userId) {
        return membershipRepository.findByUserId(userId).stream()
                .map(m -> m.getOrganization().getId())
                .collect(Collectors.toList());
    }

    private Organization resolveSingleOrganization(UUID userId) {
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            throw new OrganizationNotFoundException(UUID.randomUUID());
        }
        UUID primary = orgIds.get(0);
        return organizationRepository.findById(primary)
                .orElseThrow(() -> new OrganizationNotFoundException(primary));
    }

    private void requireWritePermission(UUID userId, UUID orgId) {
        var roleOpt = organizationAccessService.userRole(userId, orgId);
        MembershipRole role = roleOpt.orElseThrow(() -> new AccessDeniedException("No membership"));
        if (role == MembershipRole.VIEWER || role == MembershipRole.EMPLOYEE) {
            throw new AccessDeniedException("Role " + role + " cannot modify journal entries");
        }
    }

    private void validateLines(List<JournalEntryLineRequest> lines, Organization org) {
        if (lines == null || lines.size() < 2) {
            throw new InvalidJournalEntryException("Journal entry must have at least 2 lines");
        }
        boolean hasDebit = false;
        boolean hasCredit = false;
        for (JournalEntryLineRequest line : lines) {
            if (line.accountId() == null) {
                throw new InvalidAccountForJournalEntryException("Account must not be null");
            }
            Account account = accountRepository.findById(line.accountId())
                    .orElseThrow(() -> new InvalidAccountForJournalEntryException(line.accountId()));
            if (!account.getOrganization().getId().equals(org.getId())) {
                throw new InvalidAccountForJournalEntryException("Account " + line.accountId() + " belongs to different organization");
            }
            if (Boolean.FALSE.equals(account.getActive())) {
                throw new InvalidAccountForJournalEntryException("Account " + account.getName() + " is inactive");
            }

            BigDecimal debit = line.debitAmount() != null ? line.debitAmount() : BigDecimal.ZERO;
            BigDecimal credit = line.creditAmount() != null ? line.creditAmount() : BigDecimal.ZERO;

            if (debit.compareTo(BigDecimal.ZERO) < 0 || credit.compareTo(BigDecimal.ZERO) < 0) {
                throw new InvalidJournalEntryException("Amounts must not be negative");
            }
            boolean isDebit = debit.compareTo(BigDecimal.ZERO) > 0;
            boolean isCredit = credit.compareTo(BigDecimal.ZERO) > 0;

            if (isDebit && isCredit) {
                throw new InvalidJournalEntryException("Line must have either debit or credit, not both");
            }
            if (!isDebit && !isCredit) {
                throw new InvalidJournalEntryException("Line must have either debit > 0 or credit > 0");
            }
            if (isDebit) hasDebit = true;
            if (isCredit) hasCredit = true;
        }
        if (!hasDebit || !hasCredit) {
            throw new InvalidJournalEntryException("Journal entry must have at least one debit and one credit line");
        }
    }

    private BigDecimal[] calculateTotals(List<JournalEntryLineRequest> lines) {
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        for (JournalEntryLineRequest line : lines) {
            BigDecimal debit = line.debitAmount() != null ? line.debitAmount() : BigDecimal.ZERO;
            BigDecimal credit = line.creditAmount() != null ? line.creditAmount() : BigDecimal.ZERO;
            totalDebits = totalDebits.add(debit);
            totalCredits = totalCredits.add(credit);
        }
        return new BigDecimal[]{totalDebits, totalCredits};
    }

    private BigDecimal[] calculateTotalsFromEntity(JournalEntry entry) {
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        for (JournalEntryLine line : entry.getLines()) {
            totalDebits = totalDebits.add(line.getDebitAmount() != null ? line.getDebitAmount() : BigDecimal.ZERO);
            totalCredits = totalCredits.add(line.getCreditAmount() != null ? line.getCreditAmount() : BigDecimal.ZERO);
        }
        return new BigDecimal[]{totalDebits, totalCredits};
    }

    @Transactional
    public JournalEntryResponse createDraft(UUID userId, CreateJournalEntryRequest request) {
        Organization org = resolveSingleOrganization(userId);
        requireWritePermission(userId, org.getId());

        if (!StringUtils.hasText(request.entryNumber())) {
            throw new InvalidJournalEntryException("entryNumber is required");
        }
        if (request.entryDate() == null) {
            throw new InvalidJournalEntryException("entryDate is required");
        }
        if (journalEntryRepository.existsByOrganizationIdAndEntryNumber(org.getId(), request.entryNumber().trim())) {
            throw new JournalEntryAlreadyExistsException(request.entryNumber());
        }

        validateLines(request.lines(), org);

        User user = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("User not found"));

        JournalEntry entry = new JournalEntry(
                org,
                request.entryNumber().trim(),
                request.entryDate(),
                request.description(),
                request.reference(),
                user
        );
        entry.setStatus(JournalEntryStatus.DRAFT);

        for (JournalEntryLineRequest lineReq : request.lines()) {
            Account account = accountRepository.findById(lineReq.accountId()).orElseThrow();
            JournalEntryLine line = new JournalEntryLine(
                    entry,
                    account,
                    lineReq.description(),
                    lineReq.debitAmount() != null ? lineReq.debitAmount() : BigDecimal.ZERO,
                    lineReq.creditAmount() != null ? lineReq.creditAmount() : BigDecimal.ZERO
            );
            entry.addLine(line);
        }

        try {
            JournalEntry saved = journalEntryRepository.save(entry);
            BigDecimal[] totals = calculateTotalsFromEntity(saved);
            auditLogService.record(new AuditCommand(AuditAction.JOURNAL_ENTRY_CREATED,
                    AuditResourceType.JOURNAL_ENTRY, org.getId(), userId, saved.getId(),
                    "Journal entry created",
                    null, auditLogService.toJson(AuditSnapshots.journal(saved.getEntryNumber(),
                            saved.getEntryDate(), saved.getStatus() != null ? saved.getStatus().name() : null,
                            saved.getDescription(), saved.getReference(), totals[0], totals[1],
                            saved.getLines() != null ? saved.getLines().size() : 0)),
                    auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new JournalEntryAlreadyExistsException(request.entryNumber());
        }
    }

    @Transactional(readOnly = true)
    public JournalEntryResponse getJournalEntry(UUID userId, UUID id) {
        JournalEntry entry = journalEntryRepository.findById(id)
                .orElseThrow(() -> new JournalEntryNotFoundException(id));
        if (!accessibleOrganizationIds(userId).contains(entry.getOrganization().getId())) {
            throw new JournalEntryNotFoundException(id);
        }
        return toResponse(entry);
    }

    @Transactional(readOnly = true)
    public Page<JournalEntryResponse> listJournalEntries(UUID userId, JournalEntryStatus status,
                                                         LocalDate entryDate, String entryNumber,
                                                         String reference, Pageable pageable) {
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            return Page.empty(pageable);
        }

        Page<JournalEntry> page;
        // Simple filtering: prioritize specific filters if provided
        if (status != null && entryDate != null) {
            page = journalEntryRepository.findByOrganizationIdInAndStatusAndEntryDate(orgIds, status, entryDate, pageable);
        } else if (status != null) {
            page = journalEntryRepository.findByOrganizationIdInAndStatus(orgIds, status, pageable);
        } else if (entryDate != null) {
            page = journalEntryRepository.findByOrganizationIdInAndEntryDate(orgIds, entryDate, pageable);
        } else if (StringUtils.hasText(entryNumber) || StringUtils.hasText(reference)) {
            String search = StringUtils.hasText(entryNumber) ? entryNumber : reference;
            page = journalEntryRepository.searchByEntryNumberOrReference(orgIds, search.trim(), pageable);
        } else {
            page = journalEntryRepository.findByOrganizationIdIn(orgIds, pageable);
        }
        return page.map(this::toResponse);
    }

    @Transactional
    public JournalEntryResponse updateDraft(UUID userId, UUID id, UpdateJournalEntryRequest request) {
        JournalEntry entry = journalEntryRepository.findById(id)
                .orElseThrow(() -> new JournalEntryNotFoundException(id));
        if (!accessibleOrganizationIds(userId).contains(entry.getOrganization().getId())) {
            throw new JournalEntryNotFoundException(id);
        }
        if (entry.getStatus() != JournalEntryStatus.DRAFT) {
            throw new PostedJournalEntryModificationException(id);
        }
        requireWritePermission(userId, entry.getOrganization().getId());
        BigDecimal[] updateBeforeTotals = calculateTotalsFromEntity(entry);
        var updateBefore = AuditSnapshots.journal(entry.getEntryNumber(), entry.getEntryDate(),
                entry.getStatus() != null ? entry.getStatus().name() : null, entry.getDescription(),
                entry.getReference(), updateBeforeTotals[0], updateBeforeTotals[1],
                entry.getLines() != null ? entry.getLines().size() : 0);

        if (request.entryNumber() != null && StringUtils.hasText(request.entryNumber())
                && !request.entryNumber().trim().equals(entry.getEntryNumber())) {
            if (journalEntryRepository.existsByOrganizationIdAndEntryNumber(entry.getOrganization().getId(), request.entryNumber().trim())) {
                throw new JournalEntryAlreadyExistsException(request.entryNumber().trim());
            }
            entry.setEntryNumber(request.entryNumber().trim());
        }
        if (request.entryDate() != null) entry.setEntryDate(request.entryDate());
        if (request.description() != null) entry.setDescription(request.description());
        if (request.reference() != null) entry.setReference(request.reference());

        if (request.lines() != null) {
            if (request.lines().size() < 2) {
                throw new InvalidJournalEntryException("Journal entry must have at least 2 lines");
            }
            validateLines(request.lines().stream().map(r -> new JournalEntryLineRequest(
                    r.accountId(), r.description(), r.debitAmount(), r.creditAmount()
            )).toList(), entry.getOrganization());

            entry.getLines().clear();
            for (JournalEntryLineRequest lineReq : request.lines()) {
                Account account = accountRepository.findById(lineReq.accountId()).orElseThrow();
                JournalEntryLine line = new JournalEntryLine(
                        entry,
                        account,
                        lineReq.description(),
                        lineReq.debitAmount() != null ? lineReq.debitAmount() : BigDecimal.ZERO,
                        lineReq.creditAmount() != null ? lineReq.creditAmount() : BigDecimal.ZERO
                );
                entry.addLine(line);
            }
        }

        JournalEntry saved = journalEntryRepository.save(entry);
        BigDecimal[] updateAfterTotals = calculateTotalsFromEntity(saved);
        auditLogService.record(new AuditCommand(AuditAction.JOURNAL_ENTRY_UPDATED,
                AuditResourceType.JOURNAL_ENTRY, saved.getOrganization().getId(), userId, saved.getId(),
                "Journal entry updated",
                auditLogService.toJson(updateBefore),
                auditLogService.toJson(AuditSnapshots.journal(saved.getEntryNumber(), saved.getEntryDate(),
                        saved.getStatus() != null ? saved.getStatus().name() : null, saved.getDescription(),
                        saved.getReference(), updateAfterTotals[0], updateAfterTotals[1],
                        saved.getLines() != null ? saved.getLines().size() : 0)),
                auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
        return toResponse(saved);
    }

    @Transactional
    public JournalEntryResponse postJournalEntry(UUID userId, UUID id) {
        JournalEntry entry = journalEntryRepository.findById(id)
                .orElseThrow(() -> new JournalEntryNotFoundException(id));
        if (!accessibleOrganizationIds(userId).contains(entry.getOrganization().getId())) {
            throw new JournalEntryNotFoundException(id);
        }
        if (entry.getStatus() != JournalEntryStatus.DRAFT) {
            throw new InvalidJournalEntryStatusException("Only DRAFT entries can be posted. Current status: " + entry.getStatus());
        }
        requireWritePermission(userId, entry.getOrganization().getId());

        // Validate lines again for posting
        List<JournalEntryLineRequest> lineRequests = entry.getLines().stream()
                .map(l -> new JournalEntryLineRequest(l.getAccount().getId(), l.getDescription(), l.getDebitAmount(), l.getCreditAmount()))
                .toList();
        validateLines(lineRequests, entry.getOrganization());

        BigDecimal[] totals = calculateTotalsFromEntity(entry);
        BigDecimal totalDebits = totals[0];
        BigDecimal totalCredits = totals[1];
        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new UnbalancedJournalEntryException(totalDebits, totalCredits);
        }

        // Verify all accounts still active
        for (JournalEntryLine line : entry.getLines()) {
            if (Boolean.FALSE.equals(line.getAccount().getActive())) {
                throw new InvalidAccountForJournalEntryException("Account " + line.getAccount().getName() + " is inactive");
            }
        }

        entry.setStatus(JournalEntryStatus.POSTED);
        JournalEntry saved = journalEntryRepository.save(entry);
        BigDecimal[] postedTotals = calculateTotalsFromEntity(saved);
        auditLogService.record(new AuditCommand(AuditAction.JOURNAL_ENTRY_POSTED,
                AuditResourceType.JOURNAL_ENTRY, saved.getOrganization().getId(), userId, saved.getId(),
                "Journal entry posted",
                auditLogService.toJson(AuditSnapshots.journal(saved.getEntryNumber(), saved.getEntryDate(),
                        JournalEntryStatus.DRAFT.name(), saved.getDescription(), saved.getReference(),
                        postedTotals[0], postedTotals[1],
                        saved.getLines() != null ? saved.getLines().size() : 0)),
                auditLogService.toJson(AuditSnapshots.journal(saved.getEntryNumber(), saved.getEntryDate(),
                        JournalEntryStatus.POSTED.name(), saved.getDescription(), saved.getReference(),
                        postedTotals[0], postedTotals[1],
                        saved.getLines() != null ? saved.getLines().size() : 0)),
                auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
        return toResponse(saved);
    }

    @Transactional
    public JournalEntryResponse voidJournalEntry(UUID userId, UUID id) {
        JournalEntry entry = journalEntryRepository.findById(id)
                .orElseThrow(() -> new JournalEntryNotFoundException(id));
        if (!accessibleOrganizationIds(userId).contains(entry.getOrganization().getId())) {
            throw new JournalEntryNotFoundException(id);
        }
        if (entry.getStatus() == JournalEntryStatus.POSTED) {
            throw new InvalidJournalEntryStatusException("POSTED entries cannot be voided via this operation. Use reversal.");
        }
        if (entry.getStatus() == JournalEntryStatus.VOID) {
            throw new InvalidJournalEntryStatusException("Entry is already VOID");
        }
        requireWritePermission(userId, entry.getOrganization().getId());
        JournalEntryStatus voidBefore = entry.getStatus();
        BigDecimal[] voidTotals = calculateTotalsFromEntity(entry);

        entry.setStatus(JournalEntryStatus.VOID);
        JournalEntry saved = journalEntryRepository.save(entry);
        auditLogService.record(new AuditCommand(AuditAction.JOURNAL_ENTRY_VOIDED,
                AuditResourceType.JOURNAL_ENTRY, saved.getOrganization().getId(), userId, saved.getId(),
                "Journal entry voided",
                auditLogService.toJson(AuditSnapshots.journal(saved.getEntryNumber(), saved.getEntryDate(),
                        voidBefore != null ? voidBefore.name() : null, saved.getDescription(),
                        saved.getReference(), voidTotals[0], voidTotals[1],
                        saved.getLines() != null ? saved.getLines().size() : 0)),
                auditLogService.toJson(AuditSnapshots.journal(saved.getEntryNumber(), saved.getEntryDate(),
                        JournalEntryStatus.VOID.name(), saved.getDescription(), saved.getReference(),
                        voidTotals[0], voidTotals[1],
                        saved.getLines() != null ? saved.getLines().size() : 0)),
                auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
        return toResponse(saved);
    }

    private JournalEntryResponse toResponse(JournalEntry entry) {
        BigDecimal[] totals = calculateTotalsFromEntity(entry);
        List<JournalEntryLineResponse> lineResponses = entry.getLines().stream()
                .map(line -> new JournalEntryLineResponse(
                        line.getId(),
                        line.getAccount().getId(),
                        line.getAccount().getCode(),
                        line.getAccount().getName(),
                        line.getDescription(),
                        line.getDebitAmount(),
                        line.getCreditAmount()
                ))
                .collect(Collectors.toList());

        return new JournalEntryResponse(
                entry.getId(),
                entry.getOrganization().getId(),
                entry.getOrganization().getName(),
                entry.getEntryNumber(),
                entry.getEntryDate(),
                entry.getDescription(),
                entry.getReference(),
                entry.getStatus(),
                entry.getCreatedBy().getId(),
                entry.getCreatedBy().getEmail(),
                entry.getCreatedAt(),
                entry.getUpdatedAt(),
                lineResponses,
                totals[0],
                totals[1]
        );
    }

    public JournalEntry getJournalEntryEntity(UUID id) {
        return journalEntryRepository.findById(id)
                .orElseThrow(() -> new JournalEntryNotFoundException(id));
    }
}
