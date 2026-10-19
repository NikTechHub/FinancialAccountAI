package com.example.accounting.service;

import com.example.accounting.audit.AuditAction;
import com.example.accounting.audit.AuditCommand;
import com.example.accounting.audit.AuditLogService;
import com.example.accounting.audit.AuditResourceType;
import com.example.accounting.audit.AuditSnapshots;
import com.example.accounting.dto.CreateExpenseRequest;
import com.example.accounting.dto.ExpenseResponse;
import com.example.accounting.dto.JournalEntryLineRequest;
import com.example.accounting.dto.UpdateExpenseRequest;
import com.example.accounting.entity.*;
import com.example.accounting.exception.*;
import com.example.accounting.repository.*;
import com.example.accounting.security.OrganizationAccessService;
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
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationAccessService organizationAccessService;
    private final VendorRepository vendorRepository;
    private final AccountRepository accountRepository;
    private final JournalEntryService journalEntryService;
    private final UserRepository userRepository;
    private final com.example.accounting.repository.JournalEntryRepository journalEntryRepository;
    private final AuditLogService auditLogService;

    public ExpenseService(ExpenseRepository expenseRepository,
                          OrganizationRepository organizationRepository,
                          OrganizationMembershipRepository membershipRepository,
                          OrganizationAccessService organizationAccessService,
                          VendorRepository vendorRepository,
                          AccountRepository accountRepository,
                          JournalEntryService journalEntryService,
                          UserRepository userRepository,
                          com.example.accounting.repository.JournalEntryRepository journalEntryRepository,
                          AuditLogService auditLogService) {
        this.expenseRepository = expenseRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.organizationAccessService = organizationAccessService;
        this.vendorRepository = vendorRepository;
        this.accountRepository = accountRepository;
        this.journalEntryService = journalEntryService;
        this.userRepository = userRepository;
        this.journalEntryRepository = journalEntryRepository;
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
            throw new AccessDeniedException("Role " + role + " cannot modify expenses");
        }
    }

    private void validateAccounts(UUID userId, Organization org, UUID expenseAccountId, UUID paymentAccountId) {
        Account expenseAccount = accountRepository.findById(expenseAccountId)
                .orElseThrow(() -> new InvalidExpenseAccountException("Expense account not found: " + expenseAccountId));
        Account paymentAccount = accountRepository.findById(paymentAccountId)
                .orElseThrow(() -> new InvalidExpenseAccountException("Payment account not found: " + paymentAccountId));

        if (!expenseAccount.getOrganization().getId().equals(org.getId())) {
            throw new InvalidExpenseAccountException("Expense account belongs to different organization");
        }
        if (!paymentAccount.getOrganization().getId().equals(org.getId())) {
            throw new InvalidExpenseAccountException("Payment account belongs to different organization");
        }
        if (Boolean.FALSE.equals(expenseAccount.getActive())) {
            throw new InvalidExpenseAccountException("Expense account is inactive: " + expenseAccount.getName());
        }
        if (Boolean.FALSE.equals(paymentAccount.getActive())) {
            throw new InvalidExpenseAccountException("Payment account is inactive: " + paymentAccount.getName());
        }
        if (expenseAccount.getId().equals(paymentAccount.getId())) {
            throw new InvalidExpenseAccountException("Expense and payment accounts must be different");
        }
    }

    private Vendor validateVendor(UUID userId, Organization org, UUID vendorId) {
        if (vendorId == null) return null;
        Vendor vendor = vendorRepository.findById(vendorId)
                .orElseThrow(() -> new InvalidExpenseVendorException("Vendor not found: " + vendorId));
        if (!vendor.getOrganization().getId().equals(org.getId())) {
            throw new InvalidExpenseVendorException("Vendor belongs to different organization");
        }
        if (Boolean.FALSE.equals(vendor.getActive())) {
            throw new InvalidExpenseVendorException("Vendor is inactive: " + vendor.getName());
        }
        return vendor;
    }

    @Transactional
    public ExpenseResponse createDraft(UUID userId, CreateExpenseRequest request) {
        Organization org = resolveSingleOrganization(userId);
        requireWritePermission(userId, org.getId());

        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidExpenseException("Amount must be greater than 0");
        }

        validateAccounts(userId, org, request.expenseAccountId(), request.paymentAccountId());
        Vendor vendor = validateVendor(userId, org, request.vendorId());

        User user = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("User not found"));

        Expense expense = new Expense(
                org, vendor,
                accountRepository.findById(request.expenseAccountId()).orElseThrow(),
                accountRepository.findById(request.paymentAccountId()).orElseThrow(),
                request.amount(),
                request.expenseDate(),
                request.description(),
                request.reference(),
                user
        );
        expense.setStatus(ExpenseStatus.DRAFT);
        Expense saved = expenseRepository.save(expense);
        auditLogService.record(new AuditCommand(AuditAction.EXPENSE_CREATED, AuditResourceType.EXPENSE,
                org.getId(), userId, saved.getId(), "Expense created",
                null, auditLogService.toJson(AuditSnapshots.expense(
                        saved.getVendor() != null ? saved.getVendor().getId() : null,
                        saved.getExpenseAccount().getId(), saved.getPaymentAccount().getId(),
                        saved.getAmount(), saved.getExpenseDate(),
                        saved.getStatus() != null ? saved.getStatus().name() : null,
                        null, saved.getReference())),
                auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public ExpenseResponse getExpense(UUID userId, UUID expenseId) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new ExpenseNotFoundException(expenseId));
        if (!accessibleOrganizationIds(userId).contains(expense.getOrganization().getId())) {
            throw new ExpenseNotFoundException(expenseId);
        }
        return toResponse(expense);
    }

    @Transactional(readOnly = true)
    public Page<ExpenseResponse> listExpenses(UUID userId, ExpenseStatus status, UUID vendorId,
                                             UUID expenseAccountId, LocalDate expenseDate,
                                             String reference, Pageable pageable) {
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            return Page.empty(pageable);
        }
        // Prioritize filters in order: status, vendor, account, date, reference
        Page<Expense> page;
        if (status != null) {
            page = expenseRepository.findByOrganizationIdInAndStatus(orgIds, status, pageable);
        } else if (vendorId != null) {
            page = expenseRepository.findByOrganizationIdInAndVendorId(orgIds, vendorId, pageable);
        } else if (expenseAccountId != null) {
            page = expenseRepository.findByOrganizationIdInAndExpenseAccountId(orgIds, expenseAccountId, pageable);
        } else if (expenseDate != null) {
            page = expenseRepository.findByOrganizationIdInAndExpenseDate(orgIds, expenseDate, pageable);
        } else if (StringUtils.hasText(reference)) {
            page = expenseRepository.searchByReferenceOrDescription(orgIds, reference.trim(), pageable);
        } else {
            page = expenseRepository.findByOrganizationIdIn(orgIds, pageable);
        }
        return page.map(this::toResponse);
    }

    @Transactional
    public ExpenseResponse updateDraft(UUID userId, UUID expenseId, UpdateExpenseRequest request) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new ExpenseNotFoundException(expenseId));
        if (!accessibleOrganizationIds(userId).contains(expense.getOrganization().getId())) {
            throw new ExpenseNotFoundException(expenseId);
        }
        if (expense.getStatus() != ExpenseStatus.DRAFT) {
            throw new RecordedExpenseModificationException(expenseId);
        }
        requireWritePermission(userId, expense.getOrganization().getId());

        var expenseBefore = AuditSnapshots.expense(
                expense.getVendor() != null ? expense.getVendor().getId() : null,
                expense.getExpenseAccount().getId(), expense.getPaymentAccount().getId(),
                expense.getAmount(), expense.getExpenseDate(),
                expense.getStatus() != null ? expense.getStatus().name() : null,
                expense.getJournalEntry() != null ? expense.getJournalEntry().getId() : null,
                expense.getReference());

        // Resolve new accounts if provided
        UUID newExpenseAccountId = request.expenseAccountId() != null ? request.expenseAccountId() : expense.getExpenseAccount().getId();
        UUID newPaymentAccountId = request.paymentAccountId() != null ? request.paymentAccountId() : expense.getPaymentAccount().getId();
        if (request.expenseAccountId() != null || request.paymentAccountId() != null) {
            validateAccounts(userId, expense.getOrganization(), newExpenseAccountId, newPaymentAccountId);
            if (request.expenseAccountId() != null) {
                expense.setExpenseAccount(accountRepository.findById(request.expenseAccountId()).orElseThrow());
            }
            if (request.paymentAccountId() != null) {
                expense.setPaymentAccount(accountRepository.findById(request.paymentAccountId()).orElseThrow());
            }
        }

        if (request.vendorId() != null) {
            Vendor vendor = validateVendor(userId, expense.getOrganization(), request.vendorId());
            expense.setVendor(vendor);
        } else if (request.vendorId() == null && request.expenseAccountId() != null) {
            // if vendorId explicitly null in update, we could clear vendor - but UpdateExpenseRequest vendorId null means no change
            // To clear, client should send explicit null and we handle separately - for now null means no change
        }

        if (request.amount() != null) {
            if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidExpenseException("Amount must be greater than 0");
            }
            expense.setAmount(request.amount());
        }
        if (request.expenseDate() != null) expense.setExpenseDate(request.expenseDate());
        if (request.description() != null) expense.setDescription(request.description());
        if (request.reference() != null) expense.setReference(request.reference());

        Expense saved = expenseRepository.save(expense);
        auditLogService.record(new AuditCommand(AuditAction.EXPENSE_UPDATED, AuditResourceType.EXPENSE,
                saved.getOrganization().getId(), userId, saved.getId(), "Expense updated",
                auditLogService.toJson(expenseBefore),
                auditLogService.toJson(AuditSnapshots.expense(
                        saved.getVendor() != null ? saved.getVendor().getId() : null,
                        saved.getExpenseAccount().getId(), saved.getPaymentAccount().getId(),
                        saved.getAmount(), saved.getExpenseDate(),
                        saved.getStatus() != null ? saved.getStatus().name() : null,
                        saved.getJournalEntry() != null ? saved.getJournalEntry().getId() : null,
                        saved.getReference())),
                auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
        return toResponse(saved);
    }

    @Transactional
    public ExpenseResponse recordExpense(UUID userId, UUID expenseId) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new ExpenseNotFoundException(expenseId));
        if (!accessibleOrganizationIds(userId).contains(expense.getOrganization().getId())) {
            throw new ExpenseNotFoundException(expenseId);
        }
        if (expense.getStatus() != ExpenseStatus.DRAFT) {
            throw new ExpenseStatusException("Only DRAFT expenses can be recorded. Current: " + expense.getStatus());
        }
        requireWritePermission(userId, expense.getOrganization().getId());

        // Validate still active
        if (expense.getVendor() != null && Boolean.FALSE.equals(expense.getVendor().getActive())) {
            throw new InvalidExpenseVendorException("Vendor is inactive");
        }
        if (Boolean.FALSE.equals(expense.getExpenseAccount().getActive())) {
            throw new InvalidExpenseAccountException("Expense account is inactive");
        }
        if (Boolean.FALSE.equals(expense.getPaymentAccount().getActive())) {
            throw new InvalidExpenseAccountException("Payment account is inactive");
        }
        if (expense.getAmount() == null || expense.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidExpenseException("Amount must be greater than 0");
        }

        if (expense.getJournalEntry() != null) {
            throw new ExpenseStatusException("Expense already has journal entry");
        }
        // Generate unique entryNumber per org using full UUID to avoid collision
        String entryNumber = "EXP-" + expense.getId().toString().replace("-", "").toUpperCase().substring(0, 12);

        com.example.accounting.dto.CreateJournalEntryRequest journalRequest = new com.example.accounting.dto.CreateJournalEntryRequest(
                entryNumber,
                expense.getExpenseDate(),
                expense.getDescription() != null ? expense.getDescription() : "Expense: " + expense.getAmount(),
                expense.getReference(),
                List.of(
                        new com.example.accounting.dto.JournalEntryLineRequest(
                                expense.getExpenseAccount().getId(),
                                expense.getDescription(),
                                expense.getAmount(),
                                BigDecimal.ZERO
                        ),
                        new com.example.accounting.dto.JournalEntryLineRequest(
                                expense.getPaymentAccount().getId(),
                                expense.getDescription(),
                                BigDecimal.ZERO,
                                expense.getAmount()
                        )
                )
        );

        var journalResponse = journalEntryService.createDraft(userId, journalRequest);
        var posted = journalEntryService.postJournalEntry(userId, journalResponse.id());

        var journalEntry = journalEntryRepository.findById(posted.id())
                .orElseThrow(() -> new RuntimeException("Journal entry not found after posting"));

        expense.setJournalEntry(journalEntry);
        expense.setStatus(ExpenseStatus.RECORDED);
        Expense saved = expenseRepository.save(expense);
        auditLogService.record(new AuditCommand(AuditAction.EXPENSE_RECORDED, AuditResourceType.EXPENSE,
                saved.getOrganization().getId(), userId, saved.getId(), "Expense recorded",
                auditLogService.toJson(AuditSnapshots.expense(
                        saved.getVendor() != null ? saved.getVendor().getId() : null,
                        saved.getExpenseAccount().getId(), saved.getPaymentAccount().getId(),
                        saved.getAmount(), saved.getExpenseDate(),
                        ExpenseStatus.DRAFT.name(), null, saved.getReference())),
                auditLogService.toJson(AuditSnapshots.expense(
                        saved.getVendor() != null ? saved.getVendor().getId() : null,
                        saved.getExpenseAccount().getId(), saved.getPaymentAccount().getId(),
                        saved.getAmount(), saved.getExpenseDate(),
                        ExpenseStatus.RECORDED.name(), journalEntry.getId(), saved.getReference())),
                auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
        return toResponse(saved);
    }

    @Transactional
    public ExpenseResponse voidDraft(UUID userId, UUID expenseId) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new ExpenseNotFoundException(expenseId));
        if (!accessibleOrganizationIds(userId).contains(expense.getOrganization().getId())) {
            throw new ExpenseNotFoundException(expenseId);
        }
        if (expense.getStatus() == ExpenseStatus.RECORDED) {
            throw new ExpenseStatusException("RECORDED expenses cannot be voided via this operation");
        }
        if (expense.getStatus() == ExpenseStatus.VOID) {
            throw new ExpenseStatusException("Expense is already VOID");
        }
        requireWritePermission(userId, expense.getOrganization().getId());
        ExpenseStatus voidBefore = expense.getStatus();
        expense.setStatus(ExpenseStatus.VOID);
        Expense saved = expenseRepository.save(expense);
        auditLogService.record(new AuditCommand(AuditAction.EXPENSE_VOIDED, AuditResourceType.EXPENSE,
                saved.getOrganization().getId(), userId, saved.getId(), "Expense voided",
                auditLogService.toJson(AuditSnapshots.expense(
                        saved.getVendor() != null ? saved.getVendor().getId() : null,
                        saved.getExpenseAccount().getId(), saved.getPaymentAccount().getId(),
                        saved.getAmount(), saved.getExpenseDate(),
                        voidBefore != null ? voidBefore.name() : null, null, saved.getReference())),
                auditLogService.toJson(AuditSnapshots.expense(
                        saved.getVendor() != null ? saved.getVendor().getId() : null,
                        saved.getExpenseAccount().getId(), saved.getPaymentAccount().getId(),
                        saved.getAmount(), saved.getExpenseDate(),
                        ExpenseStatus.VOID.name(), null, saved.getReference())),
                auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
        return toResponse(saved);
    }

    private ExpenseResponse toResponse(Expense e) {
        return new ExpenseResponse(
                e.getId(),
                e.getOrganization().getId(),
                e.getOrganization().getName(),
                e.getVendor() != null ? e.getVendor().getId() : null,
                e.getVendor() != null ? e.getVendor().getName() : null,
                e.getExpenseAccount().getId(),
                e.getExpenseAccount().getCode(),
                e.getExpenseAccount().getName(),
                e.getPaymentAccount().getId(),
                e.getPaymentAccount().getCode(),
                e.getPaymentAccount().getName(),
                e.getAmount(),
                e.getExpenseDate(),
                e.getDescription(),
                e.getReference(),
                e.getStatus(),
                e.getJournalEntry() != null ? e.getJournalEntry().getId() : null,
                e.getCreatedBy().getId(),
                e.getCreatedBy().getEmail(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }
}
