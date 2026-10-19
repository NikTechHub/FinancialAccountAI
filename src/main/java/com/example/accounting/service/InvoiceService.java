package com.example.accounting.service;

import com.example.accounting.audit.AuditAction;
import com.example.accounting.audit.AuditCommand;
import com.example.accounting.audit.AuditLogService;
import com.example.accounting.audit.AuditResourceType;
import com.example.accounting.audit.AuditSnapshots;
import com.example.accounting.dto.*;
import com.example.accounting.entity.*;
import com.example.accounting.exception.*;
import com.example.accounting.repository.*;
import com.example.accounting.security.OrganizationAccessService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationAccessService organizationAccessService;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final JournalEntryService journalEntryService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          InvoiceLineRepository invoiceLineRepository,
                          OrganizationRepository organizationRepository,
                          OrganizationMembershipRepository membershipRepository,
                          OrganizationAccessService organizationAccessService,
                          CustomerRepository customerRepository,
                          AccountRepository accountRepository,
                          JournalEntryService journalEntryService,
                          UserRepository userRepository,
                          AuditLogService auditLogService) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceLineRepository = invoiceLineRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.organizationAccessService = organizationAccessService;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.journalEntryService = journalEntryService;
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
            throw new AccessDeniedException("Role " + role + " cannot modify invoices");
        }
    }

    private void requireReadPermission(UUID userId, UUID orgId) {
        if (!membershipRepository.existsByUserIdAndOrganizationId(userId, orgId)) {
            throw new AccessDeniedException("No membership");
        }
    }

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private BigDecimal round(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO;
        return value.setScale(SCALE, ROUNDING);
    }

    private void validateLine(InvoiceLineRequest line, Organization org) {
        if (line.quantity() == null || line.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidInvoiceLineException("quantity must be greater than 0");
        }
        if (line.unitPrice() == null || line.unitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidInvoiceLineException("unitPrice must not be negative");
        }
        if (line.taxRate() == null) {
            throw new InvalidInvoiceLineException("taxRate must not be null");
        }
        if (line.taxRate().compareTo(BigDecimal.ZERO) < 0 || line.taxRate().compareTo(new BigDecimal("100")) > 0) {
            throw new InvalidInvoiceLineException("taxRate must be between 0 and 100");
        }
        if (line.discountAmount() == null) {
            throw new InvalidInvoiceLineException("discountAmount must not be null");
        }
        if (line.discountAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidInvoiceLineException("discountAmount must not be negative");
        }
        if (line.revenueAccountId() == null) {
            throw new InvalidInvoiceAccountException("revenueAccountId must not be null");
        }
        Account revenueAccount = accountRepository.findById(line.revenueAccountId())
                .orElseThrow(() -> new InvalidInvoiceAccountException("Revenue account not found: " + line.revenueAccountId()));
        if (!revenueAccount.getOrganization().getId().equals(org.getId())) {
            throw new InvalidInvoiceAccountException("Revenue account belongs to different organization");
        }
        if (Boolean.FALSE.equals(revenueAccount.getActive())) {
            throw new InvalidInvoiceAccountException("Revenue account is inactive: " + revenueAccount.getName());
        }
        if (revenueAccount.getType() != AccountType.REVENUE) {
            throw new InvalidInvoiceAccountException("Revenue account must be of type REVENUE, was: " + revenueAccount.getType());
        }

        // lineSubtotal = quantity * unitPrice
        BigDecimal lineSubtotal = line.quantity().multiply(line.unitPrice()).setScale(SCALE, ROUNDING);
        if (line.discountAmount().compareTo(lineSubtotal) > 0) {
            throw new InvalidInvoiceLineException("discountAmount must not exceed lineSubtotal " + lineSubtotal);
        }
    }

    private Account resolveARAccount(Organization org) {
        // Try to find existing AR account: systemAccount true, type ASSET, name containing receivable or code 1100
        List<Account> accounts = accountRepository.findByOrganizationId(org.getId());
        for (Account acc : accounts) {
            if (Boolean.TRUE.equals(acc.getSystemAccount()) && acc.getType() == AccountType.ASSET && acc.getActive()
                    && acc.getName().toLowerCase().contains("receivable")) {
                return acc;
            }
        }
        for (Account acc : accounts) {
            if (acc.getType() == AccountType.ASSET && acc.getActive()
                    && "1100".equals(acc.getCode())) {
                return acc;
            }
        }
        for (Account acc : accounts) {
            if (acc.getType() == AccountType.ASSET && acc.getActive()
                    && acc.getName().toLowerCase().contains("receivable")) {
                return acc;
            }
        }
        // create if not found
        Account ar = new Account(org, "1100", "Accounts Receivable", AccountType.ASSET, null, "System AR account", true, true);
        return accountRepository.save(ar);
    }

    private InvoiceTotals calculateTotals(List<InvoiceLineRequest> lines) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;
        BigDecimal discountAmount = BigDecimal.ZERO;
        for (InvoiceLineRequest line : lines) {
            BigDecimal lineSubtotal = line.quantity().multiply(line.unitPrice()).setScale(SCALE, ROUNDING);
            BigDecimal taxable = lineSubtotal.subtract(line.discountAmount()).setScale(SCALE, ROUNDING);
            BigDecimal lineTax = taxable.multiply(line.taxRate()).divide(new BigDecimal("100"), SCALE, ROUNDING);
            // lineTotal = taxable + lineTax, but we don't need to store separately for invoice totals except via tax
            subtotal = subtotal.add(lineSubtotal);
            taxAmount = taxAmount.add(lineTax);
            discountAmount = discountAmount.add(line.discountAmount());
        }
        BigDecimal totalAmount = subtotal.subtract(discountAmount).add(taxAmount).setScale(SCALE, ROUNDING);
        return new InvoiceTotals(
                subtotal.setScale(SCALE, ROUNDING),
                taxAmount.setScale(SCALE, ROUNDING),
                discountAmount.setScale(SCALE, ROUNDING),
                totalAmount
        );
    }

    private record InvoiceTotals(BigDecimal subtotal, BigDecimal taxAmount, BigDecimal discountAmount, BigDecimal totalAmount) {}

    private InvoiceLine toLineEntity(Invoice invoice, InvoiceLineRequest req) {
        BigDecimal lineSubtotal = req.quantity().multiply(req.unitPrice()).setScale(SCALE, ROUNDING);
        BigDecimal taxable = lineSubtotal.subtract(req.discountAmount()).setScale(SCALE, ROUNDING);
        BigDecimal lineTax = taxable.multiply(req.taxRate()).divide(new BigDecimal("100"), SCALE, ROUNDING);
        BigDecimal lineTotal = taxable.add(lineTax).setScale(SCALE, ROUNDING);
        Account revenueAccount = accountRepository.findById(req.revenueAccountId()).orElseThrow();
        return new InvoiceLine(
                invoice,
                req.description(),
                req.quantity(),
                req.unitPrice(),
                req.taxRate(),
                req.discountAmount(),
                revenueAccount,
                lineSubtotal,
                lineTax,
                lineTotal
        );
    }

    @Transactional
    public InvoiceResponse createInvoice(UUID userId, CreateInvoiceRequest request) {
        Organization org = resolveSingleOrganization(userId);
        requireWritePermission(userId, org.getId());

        if (invoiceRepository.existsByOrganizationIdAndInvoiceNumber(org.getId(), request.invoiceNumber().trim())) {
            throw new InvoiceAlreadyExistsException(request.invoiceNumber());
        }

        Customer customer = customerRepository.findById(request.customerId())
                .orElseThrow(() -> new InvalidInvoiceCustomerException("Customer not found: " + request.customerId()));
        if (!customer.getOrganization().getId().equals(org.getId())) {
            throw new InvalidInvoiceCustomerException("Customer belongs to different organization");
        }
        if (Boolean.FALSE.equals(customer.getActive())) {
            throw new InvalidInvoiceCustomerException("Customer is inactive");
        }

        // Validate lines
        for (InvoiceLineRequest line : request.lines()) {
            validateLine(line, org);
        }

        InvoiceTotals totals = calculateTotals(request.lines());

        User user = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("User not found"));

        Invoice invoice = new Invoice();
        invoice.setOrganization(org);
        invoice.setCustomer(customer);
        invoice.setInvoiceNumber(request.invoiceNumber().trim());
        invoice.setInvoiceDate(request.invoiceDate());
        invoice.setDueDate(request.dueDate());
        invoice.setNotes(request.notes());
        invoice.setTerms(request.terms());
        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setCreatedBy(user);
        invoice.setSubtotal(totals.subtotal);
        invoice.setTaxAmount(totals.taxAmount);
        invoice.setDiscountAmount(totals.discountAmount);
        invoice.setTotalAmount(totals.totalAmount);
        invoice.setAmountPaid(BigDecimal.ZERO.setScale(SCALE, ROUNDING));
        invoice.setBalanceDue(totals.totalAmount);

        for (InvoiceLineRequest lineReq : request.lines()) {
            InvoiceLine line = toLineEntity(invoice, lineReq);
            invoice.addLine(line);
        }

        try {
            Invoice saved = invoiceRepository.save(invoice);
            auditLogService.record(new AuditCommand(AuditAction.INVOICE_CREATED, AuditResourceType.INVOICE,
                    org.getId(), userId, saved.getId(), "Invoice created",
                    null, auditLogService.toJson(AuditSnapshots.invoice(saved.getInvoiceNumber(),
                            saved.getInvoiceDate(),
                            saved.getStatus() != null ? saved.getStatus().name() : null,
                            saved.getSubtotal(), saved.getTaxAmount(), saved.getDiscountAmount(),
                            saved.getTotalAmount(), saved.getAmountPaid(), saved.getBalanceDue(),
                            saved.getCustomer().getId(), saved.getCustomer().getName(), null)),
                    auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new InvoiceAlreadyExistsException(request.invoiceNumber());
        }
    }

    @Transactional(readOnly = true)
    public InvoiceResponse getInvoice(UUID userId, UUID invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        if (!accessibleOrganizationIds(userId).contains(invoice.getOrganization().getId())) {
            throw new InvoiceNotFoundException(invoiceId);
        }
        return toResponse(invoice);
    }

    @Transactional(readOnly = true)
    public Page<InvoiceResponse> listInvoices(UUID userId, InvoiceStatus status, UUID customerId,
                                              String invoiceNumber, LocalDate invoiceDate, LocalDate dueDate,
                                              Pageable pageable) {
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            return Page.empty(pageable);
        }
        Page<Invoice> page;
        if (status != null) {
            page = invoiceRepository.findByOrganizationIdInAndStatus(orgIds, status, pageable);
        } else if (customerId != null) {
            page = invoiceRepository.findByOrganizationIdInAndCustomerId(orgIds, customerId, pageable);
        } else if (invoiceNumber != null && StringUtils.hasText(invoiceNumber)) {
            page = invoiceRepository.findByOrganizationIdInAndInvoiceNumberContainingIgnoreCase(orgIds, invoiceNumber.trim(), pageable);
        } else if (invoiceDate != null) {
            page = invoiceRepository.findByOrganizationIdInAndInvoiceDate(orgIds, invoiceDate, pageable);
        } else if (dueDate != null) {
            page = invoiceRepository.findByOrganizationIdInAndDueDate(orgIds, dueDate, pageable);
        } else {
            page = invoiceRepository.findByOrganizationIdIn(orgIds, pageable);
        }
        return page.map(this::toResponse);
    }

    @Transactional
    public InvoiceResponse updateInvoice(UUID userId, UUID invoiceId, UpdateInvoiceRequest request) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        if (!accessibleOrganizationIds(userId).contains(invoice.getOrganization().getId())) {
            throw new InvoiceNotFoundException(invoiceId);
        }
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new InvoiceStatusException("Only DRAFT invoices can be updated. Current: " + invoice.getStatus());
        }
        requireWritePermission(userId, invoice.getOrganization().getId());

        var invoiceBefore = AuditSnapshots.invoice(invoice.getInvoiceNumber(), invoice.getInvoiceDate(),
                invoice.getStatus() != null ? invoice.getStatus().name() : null,
                invoice.getSubtotal(), invoice.getTaxAmount(), invoice.getDiscountAmount(),
                invoice.getTotalAmount(), invoice.getAmountPaid(), invoice.getBalanceDue(),
                invoice.getCustomer().getId(), invoice.getCustomer().getName(),
                invoice.getJournalEntry() != null ? invoice.getJournalEntry().getId() : null);

        if (request.customerId() != null && !request.customerId().equals(invoice.getCustomer().getId())) {
            Customer customer = customerRepository.findById(request.customerId())
                    .orElseThrow(() -> new InvalidInvoiceCustomerException("Customer not found: " + request.customerId()));
            if (!customer.getOrganization().getId().equals(invoice.getOrganization().getId())) {
                throw new InvalidInvoiceCustomerException("Customer belongs to different organization");
            }
            if (Boolean.FALSE.equals(customer.getActive())) {
                throw new InvalidInvoiceCustomerException("Customer is inactive");
            }
            invoice.setCustomer(customer);
        }
        if (request.invoiceNumber() != null && StringUtils.hasText(request.invoiceNumber())
                && !request.invoiceNumber().trim().equals(invoice.getInvoiceNumber())) {
            if (invoiceRepository.existsByOrganizationIdAndInvoiceNumber(invoice.getOrganization().getId(), request.invoiceNumber().trim())) {
                throw new InvoiceAlreadyExistsException(request.invoiceNumber().trim());
            }
            invoice.setInvoiceNumber(request.invoiceNumber().trim());
        }
        if (request.invoiceDate() != null) invoice.setInvoiceDate(request.invoiceDate());
        if (request.dueDate() != null) invoice.setDueDate(request.dueDate());
        if (request.notes() != null) invoice.setNotes(request.notes());
        if (request.terms() != null) invoice.setTerms(request.terms());

        if (request.lines() != null) {
            if (request.lines().isEmpty()) {
                throw new InvalidInvoiceException("Invoice must have at least one line");
            }
            for (InvoiceLineRequest line : request.lines()) {
                validateLine(line, invoice.getOrganization());
            }
            invoice.getLines().clear();
            for (InvoiceLineRequest lineReq : request.lines()) {
                InvoiceLine line = toLineEntity(invoice, lineReq);
                invoice.addLine(line);
            }
            InvoiceTotals totals = calculateTotals(request.lines());
            invoice.setSubtotal(totals.subtotal);
            invoice.setTaxAmount(totals.taxAmount);
            invoice.setDiscountAmount(totals.discountAmount);
            invoice.setTotalAmount(totals.totalAmount);
            invoice.setBalanceDue(totals.totalAmount.subtract(invoice.getAmountPaid()).setScale(SCALE, ROUNDING));
        }

        Invoice saved = invoiceRepository.save(invoice);
        auditLogService.record(new AuditCommand(AuditAction.INVOICE_UPDATED, AuditResourceType.INVOICE,
                saved.getOrganization().getId(), userId, saved.getId(), "Invoice updated",
                auditLogService.toJson(invoiceBefore),
                auditLogService.toJson(AuditSnapshots.invoice(saved.getInvoiceNumber(), saved.getInvoiceDate(),
                        saved.getStatus() != null ? saved.getStatus().name() : null,
                        saved.getSubtotal(), saved.getTaxAmount(), saved.getDiscountAmount(),
                        saved.getTotalAmount(), saved.getAmountPaid(), saved.getBalanceDue(),
                        saved.getCustomer().getId(), saved.getCustomer().getName(),
                        saved.getJournalEntry() != null ? saved.getJournalEntry().getId() : null)),
                auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
        return toResponse(saved);
    }

    @Transactional
    public InvoiceResponse issueInvoice(UUID userId, UUID invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        if (!accessibleOrganizationIds(userId).contains(invoice.getOrganization().getId())) {
            throw new InvoiceNotFoundException(invoiceId);
        }
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new InvoiceStatusException("Only DRAFT invoices can be issued. Current: " + invoice.getStatus());
        }
        requireWritePermission(userId, invoice.getOrganization().getId());

        if (invoice.getLines() == null || invoice.getLines().isEmpty()) {
            throw new InvalidInvoiceException("Invoice must have at least one line");
        }
        // Validate all lines again
        List<InvoiceLineRequest> lineRequests = invoice.getLines().stream()
                .map(l -> new InvoiceLineRequest(
                        l.getDescription(),
                        l.getQuantity(),
                        l.getUnitPrice(),
                        l.getTaxRate(),
                        l.getDiscountAmount(),
                        l.getRevenueAccount().getId()
                ))
                .toList();
        for (InvoiceLineRequest lr : lineRequests) {
            validateLine(lr, invoice.getOrganization());
        }

        // Recalculate totals server-side
        InvoiceTotals totals = calculateTotals(lineRequests);
        invoice.setSubtotal(totals.subtotal);
        invoice.setTaxAmount(totals.taxAmount);
        invoice.setDiscountAmount(totals.discountAmount);
        invoice.setTotalAmount(totals.totalAmount);
        invoice.setAmountPaid(BigDecimal.ZERO.setScale(SCALE, ROUNDING));
        invoice.setBalanceDue(totals.totalAmount);

        // Resolve AR account
        Account arAccount = resolveARAccount(invoice.getOrganization());
        if (Boolean.FALSE.equals(arAccount.getActive())) {
            throw new InvalidInvoiceAccountException("AR account is inactive");
        }

        // Create journal entry: Debit AR, Credit each revenue line
        String entryNumber = "INV-" + invoice.getId().toString().replace("-", "").substring(0, 12).toUpperCase();
        // Ensure unique per org, if exists append random
        int attempts = 0;
        String baseEntryNumber = entryNumber;
        while (true) {
            try {
                // Build lines for journal
                var journalLines = new java.util.ArrayList<JournalEntryLineRequest>();
                // Debit AR total
                journalLines.add(new JournalEntryLineRequest(
                        arAccount.getId(),
                        "Invoice " + invoice.getInvoiceNumber() + " - AR",
                        invoice.getTotalAmount(),
                        BigDecimal.ZERO
                ));
                // Credits per revenue line
                for (InvoiceLine line : invoice.getLines()) {
                    BigDecimal lineTotal = line.getLineTotal(); // already calculated
                    journalLines.add(new JournalEntryLineRequest(
                            line.getRevenueAccount().getId(),
                            line.getDescription() != null ? line.getDescription() : invoice.getNotes(),
                            BigDecimal.ZERO,
                            lineTotal
                    ));
                }

                CreateJournalEntryRequest journalRequest = new CreateJournalEntryRequest(
                        entryNumber,
                        invoice.getInvoiceDate(),
                        "Invoice Issued: " + invoice.getInvoiceNumber(),
                        invoice.getInvoiceNumber(),
                        journalLines
                );

                var journalResponse = journalEntryService.createDraft(userId, journalRequest);
                var posted = journalEntryService.postJournalEntry(userId, journalResponse.id());
                var journalEntry = journalEntryService.getJournalEntryEntity(posted.id());
                invoice.setJournalEntry(journalEntry);
                invoice.setStatus(InvoiceStatus.ISSUED);
                Invoice saved = invoiceRepository.save(invoice);
                auditLogService.record(new AuditCommand(AuditAction.INVOICE_ISSUED, AuditResourceType.INVOICE,
                        saved.getOrganization().getId(), userId, saved.getId(), "Invoice issued",
                        auditLogService.toJson(AuditSnapshots.invoice(saved.getInvoiceNumber(),
                                saved.getInvoiceDate(), InvoiceStatus.DRAFT.name(),
                                saved.getSubtotal(), saved.getTaxAmount(), saved.getDiscountAmount(),
                                saved.getTotalAmount(), saved.getAmountPaid(), saved.getBalanceDue(),
                                saved.getCustomer().getId(), saved.getCustomer().getName(), null)),
                        auditLogService.toJson(AuditSnapshots.invoice(saved.getInvoiceNumber(),
                                saved.getInvoiceDate(), InvoiceStatus.ISSUED.name(),
                                saved.getSubtotal(), saved.getTaxAmount(), saved.getDiscountAmount(),
                                saved.getTotalAmount(), saved.getAmountPaid(), saved.getBalanceDue(),
                                saved.getCustomer().getId(), saved.getCustomer().getName(),
                                journalEntry.getId())),
                        auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
                return toResponse(saved);
            } catch (JournalEntryAlreadyExistsException | DataIntegrityViolationException ex) {
                if (attempts++ > 3) throw new InvoiceAccountingException("Failed to create journal entry: duplicate entry number", ex);
                entryNumber = baseEntryNumber + "-" + attempts;
            } catch (UnbalancedJournalEntryException | InvalidJournalEntryException | InvalidAccountForJournalEntryException ex) {
                throw new InvoiceAccountingException("Failed to create accounting entry: " + ex.getMessage(), ex);
            }
        }
    }

    @Transactional
    public InvoiceResponse voidInvoice(UUID userId, UUID invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        if (!accessibleOrganizationIds(userId).contains(invoice.getOrganization().getId())) {
            throw new InvoiceNotFoundException(invoiceId);
        }
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new InvoiceStatusException("Only DRAFT invoices can be voided. Current: " + invoice.getStatus());
        }
        requireWritePermission(userId, invoice.getOrganization().getId());
        invoice.setStatus(InvoiceStatus.VOID);
        Invoice saved = invoiceRepository.save(invoice);
        auditLogService.record(new AuditCommand(AuditAction.INVOICE_VOIDED, AuditResourceType.INVOICE,
                saved.getOrganization().getId(), userId, saved.getId(), "Invoice voided",
                auditLogService.toJson(AuditSnapshots.invoice(saved.getInvoiceNumber(), saved.getInvoiceDate(),
                        InvoiceStatus.DRAFT.name(),
                        saved.getSubtotal(), saved.getTaxAmount(), saved.getDiscountAmount(),
                        saved.getTotalAmount(), saved.getAmountPaid(), saved.getBalanceDue(),
                        saved.getCustomer().getId(), saved.getCustomer().getName(), null)),
                auditLogService.toJson(AuditSnapshots.invoice(saved.getInvoiceNumber(), saved.getInvoiceDate(),
                        InvoiceStatus.VOID.name(),
                        saved.getSubtotal(), saved.getTaxAmount(), saved.getDiscountAmount(),
                        saved.getTotalAmount(), saved.getAmountPaid(), saved.getBalanceDue(),
                        saved.getCustomer().getId(), saved.getCustomer().getName(), null)),
                auditLogService.toJson(AuditSnapshots.metadata("API", null)), true));
        return toResponse(saved);
    }

    private InvoiceResponse toResponse(Invoice inv) {
        List<InvoiceLineResponse> lineResponses = inv.getLines().stream()
                .map(l -> new InvoiceLineResponse(
                        l.getId(),
                        l.getDescription(),
                        l.getQuantity(),
                        l.getUnitPrice(),
                        l.getTaxRate(),
                        l.getDiscountAmount(),
                        l.getRevenueAccount().getId(),
                        l.getRevenueAccount().getCode(),
                        l.getRevenueAccount().getName(),
                        l.getLineSubtotal(),
                        l.getLineTaxAmount(),
                        l.getLineTotal()
                ))
                .collect(Collectors.toList());

        return new InvoiceResponse(
                inv.getId(),
                inv.getOrganization().getId(),
                inv.getOrganization().getName(),
                inv.getCustomer().getId(),
                inv.getCustomer().getName(),
                inv.getInvoiceNumber(),
                inv.getInvoiceDate(),
                inv.getDueDate(),
                inv.getStatus(),
                inv.getSubtotal(),
                inv.getTaxAmount(),
                inv.getDiscountAmount(),
                inv.getTotalAmount(),
                inv.getAmountPaid(),
                inv.getBalanceDue(),
                inv.getNotes(),
                inv.getTerms(),
                inv.getJournalEntry() != null ? inv.getJournalEntry().getId() : null,
                inv.getCreatedBy().getId(),
                inv.getCreatedBy().getEmail(),
                inv.getCreatedAt(),
                inv.getUpdatedAt(),
                lineResponses
        );
    }
}
