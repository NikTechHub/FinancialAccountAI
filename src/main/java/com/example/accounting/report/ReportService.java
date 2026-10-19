package com.example.accounting.report;

import com.example.accounting.audit.AuditAction;
import com.example.accounting.audit.AuditCommand;
import com.example.accounting.audit.AuditLogService;
import com.example.accounting.audit.AuditResourceType;
import com.example.accounting.audit.AuditSnapshots;
import com.example.accounting.entity.Account;
import com.example.accounting.entity.AccountType;
import com.example.accounting.entity.InvoiceStatus;
import com.example.accounting.exception.AccountNotFoundException;
import com.example.accounting.exception.CustomerNotFoundException;
import com.example.accounting.exception.VendorNotFoundException;
import com.example.accounting.repository.AccountRepository;
import com.example.accounting.repository.CustomerRepository;
import com.example.accounting.repository.OrganizationMembershipRepository;
import com.example.accounting.repository.VendorRepository;
import com.example.accounting.report.dto.BalanceSheetLine;
import com.example.accounting.report.dto.BalanceSheetResponse;
import com.example.accounting.report.dto.DashboardResponse;
import com.example.accounting.report.dto.ExpenseSummaryResponse;
import com.example.accounting.report.dto.GeneralLedgerLine;
import com.example.accounting.report.dto.InvoiceSummaryResponse;
import com.example.accounting.report.dto.ProfitAndLossLine;
import com.example.accounting.report.dto.ProfitAndLossResponse;
import com.example.accounting.report.dto.TrialBalanceLine;
import com.example.accounting.report.dto.TrialBalanceResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only reporting over posted accounting data.
 *
 * <p>Rules applied consistently:</p>
 * <ul>
 *   <li>Only {@code POSTED} journal entries affect journal-based reports;
 *       {@code DRAFT} and {@code VOID} entries are excluded.</li>
 *   <li>Expense reports use {@code RECORDED} Expense records (never their
 *       journals as well, so nothing is double-counted).</li>
 *   <li>Invoice reports use issued-type invoices
 *       ({@code ISSUED, PARTIALLY_PAID, PAID, OVERDUE}); {@code DRAFT} and
 *       {@code VOID} invoices are excluded.</li>
 *   <li>All queries are scoped to the caller's validated organization
 *       memberships; no organization ID is accepted from the client.</li>
 *   <li>Normal balances: ASSET/EXPENSE are debit-normal
 *       (balance = debits - credits); LIABILITY/EQUITY/REVENUE are
 *       credit-normal (balance = credits - debits).</li>
 *   <li>Date ranges are inclusive. No timezone conversion is applied to
 *       {@code LocalDate} accounting dates.</li>
 * </ul>
 *
 * <p>Nothing here mutates accounting data. Redis is intentionally not used:
 * financial reports must never serve stale figures.</p>
 */
@Service
public class ReportService {

    private static final List<InvoiceStatus> REPORTABLE_INVOICE_STATUSES = List.of(
            InvoiceStatus.ISSUED, InvoiceStatus.PARTIALLY_PAID, InvoiceStatus.PAID, InvoiceStatus.OVERDUE);

    private final ReportRepository reportRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final AccountRepository accountRepository;
    private final VendorRepository vendorRepository;
    private final CustomerRepository customerRepository;
    private final AuditLogService auditLogService;

    public ReportService(ReportRepository reportRepository,
                         OrganizationMembershipRepository membershipRepository,
                         AccountRepository accountRepository,
                         VendorRepository vendorRepository,
                         CustomerRepository customerRepository,
                         AuditLogService auditLogService) {
        this.reportRepository = reportRepository;
        this.membershipRepository = membershipRepository;
        this.accountRepository = accountRepository;
        this.vendorRepository = vendorRepository;
        this.customerRepository = customerRepository;
        this.auditLogService = auditLogService;
    }

    private List<UUID> accessibleOrganizationIds(UUID userId) {
        return membershipRepository.findByUserId(userId).stream()
                .map(m -> m.getOrganization().getId())
                .collect(Collectors.toList());
    }

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("fromDate and toDate are required");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("fromDate must be on or before toDate");
        }
    }

    private void validateAsOfDate(LocalDate asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException("asOfDate is required");
        }
    }

    /**
     * Net balance using normal-balance rules. Never null: JPQL SUM over a
     * group always yields a value.
     */
    static BigDecimal netBalance(AccountType type, BigDecimal debits, BigDecimal credits) {
        BigDecimal d = debits == null ? BigDecimal.ZERO : debits;
        BigDecimal c = credits == null ? BigDecimal.ZERO : credits;
        return switch (type) {
            case ASSET, EXPENSE -> d.subtract(c);
            case LIABILITY, EQUITY, REVENUE -> c.subtract(d);
        };
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @Transactional(readOnly = true)
    public TrialBalanceResponse getTrialBalance(UUID userId, LocalDate from, LocalDate to) {
        validateDateRange(from, to);
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            return new TrialBalanceResponse(from, to, List.of(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        List<AccountTotals> totals = reportRepository.accountTotalsInRange(orgIds, from, to, null);        List<TrialBalanceLine> lines = totals.stream()
                .map(t -> new TrialBalanceLine(t.accountId(), t.accountCode(), t.accountName(), t.accountType(),
                        nullToZero(t.debitTotal()), nullToZero(t.creditTotal()),
                        netBalance(t.accountType(), t.debitTotal(), t.creditTotal())))
                .toList();
        BigDecimal totalDebits = lines.stream().map(TrialBalanceLine::debitTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredits = lines.stream().map(TrialBalanceLine::creditTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Reported as-is; an inconsistent dataset yields a non-zero difference.
        TrialBalanceResponse response = new TrialBalanceResponse(from, to, lines, totalDebits, totalCredits,
                totalDebits.subtract(totalCredits));
        auditReportView(userId, orgIds, "trial-balance", from, to, null);
        return response;
    }

    @Transactional(readOnly = true)
    public Page<GeneralLedgerLine> getGeneralLedger(UUID userId, UUID accountId,
                                                    LocalDate from, LocalDate to, Pageable pageable) {
        validateDateRange(from, to);
        if (accountId == null) {
            throw new IllegalArgumentException("accountId is required");
        }
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!orgIds.contains(account.getOrganization().getId())) {
            // Same 404 convention as other services: never leak existence.
            throw new AccountNotFoundException(accountId);
        }
        long total = reportRepository.countLedgerLines(accountId, orgIds, from, to);
        int offset = (int) pageable.getOffset();
        int limit = pageable.isPaged() ? pageable.getPageSize() : Integer.MAX_VALUE;
        List<GeneralLedgerLine> lines = total == 0 ? List.of()
                : reportRepository.ledgerLines(accountId, orgIds, from, to, offset, limit);
        Page<GeneralLedgerLine> page = new PageImpl<>(lines, pageable, total);
        auditReportView(userId, orgIds, "general-ledger", from, to, accountId);
        return page;
    }

    @Transactional(readOnly = true)
    public ProfitAndLossResponse getProfitAndLoss(UUID userId, LocalDate from, LocalDate to) {
        validateDateRange(from, to);
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            return new ProfitAndLossResponse(from, to, List.of(), List.of(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        List<ProfitAndLossLine> revenueLines = reportRepository
                .accountTotalsInRange(orgIds, from, to, List.of(AccountType.REVENUE)).stream()
                .map(t -> new ProfitAndLossLine(t.accountId(), t.accountCode(), t.accountName(),
                        netBalance(AccountType.REVENUE, t.debitTotal(), t.creditTotal())))
                .toList();
        List<ProfitAndLossLine> expenseLines = reportRepository
                .accountTotalsInRange(orgIds, from, to, List.of(AccountType.EXPENSE)).stream()
                .map(t -> new ProfitAndLossLine(t.accountId(), t.accountCode(), t.accountName(),
                        netBalance(AccountType.EXPENSE, t.debitTotal(), t.creditTotal())))
                .toList();
        BigDecimal totalRevenue = revenueLines.stream().map(ProfitAndLossLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalExpenses = expenseLines.stream().map(ProfitAndLossLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        ProfitAndLossResponse response = new ProfitAndLossResponse(from, to, revenueLines, expenseLines,
                totalRevenue, totalExpenses, totalRevenue.subtract(totalExpenses));
        auditReportView(userId, orgIds, "profit-and-loss", from, to, null);
        return response;
    }

    @Transactional(readOnly = true)
    public BalanceSheetResponse getBalanceSheet(UUID userId, LocalDate asOf) {
        validateAsOfDate(asOf);
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            return new BalanceSheetResponse(asOf, List.of(), List.of(), List.of(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        // Derived purely from posted journals on or before asOf. No opening
        // balances are invented and no retained-earnings/closing logic is
        // applied: equity here is the balance of EQUITY-type accounts only.
        List<BalanceSheetLine> assets = toBalanceLines(
                reportRepository.accountTotalsAsOf(orgIds, asOf, List.of(AccountType.ASSET)));
        List<BalanceSheetLine> liabilities = toBalanceLines(
                reportRepository.accountTotalsAsOf(orgIds, asOf, List.of(AccountType.LIABILITY)));
        List<BalanceSheetLine> equity = toBalanceLines(
                reportRepository.accountTotalsAsOf(orgIds, asOf, List.of(AccountType.EQUITY)));
        BalanceSheetResponse response = new BalanceSheetResponse(asOf, assets, liabilities, equity,
                sumBalances(assets), sumBalances(liabilities), sumBalances(equity));
        // Balance sheet uses an as-of date: record from=asOf, to=asOf.
        auditReportView(userId, orgIds, "balance-sheet", asOf, asOf, null);
        return response;
    }

    private static List<BalanceSheetLine> toBalanceLines(List<AccountTotals> totals) {
        return totals.stream()
                .map(t -> new BalanceSheetLine(t.accountId(), t.accountCode(), t.accountName(), t.accountType(),
                        netBalance(t.accountType(), t.debitTotal(), t.creditTotal())))
                .toList();
    }

    private static BigDecimal sumBalances(List<BalanceSheetLine> lines) {
        return lines.stream().map(BalanceSheetLine::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Selective report-view auditing: only the four significant financial
     * reports (trial balance, general ledger, P&amp;L, balance sheet) produce
     * REPORT_VIEWED rows. Operational summaries (expenses, invoices,
     * dashboard) are intentionally not audited to avoid excessive volume.
     * Runs in a new transaction so the record survives the read-only
     * report transaction; failures are swallowed so reporting never breaks.
     * Metadata carries only report type, date range and endpoint — never
     * the report figures.
     */
    private void auditReportView(UUID userId, List<UUID> orgIds, String reportType,
                                 LocalDate from, LocalDate to, UUID accountId) {
        if (orgIds == null || orgIds.isEmpty()) {
            return;
        }
        try {
            UUID primaryOrg = orgIds.get(0);
            java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
            extra.put("reportType", reportType);
            extra.put("fromDate", from != null ? from.toString() : null);
            extra.put("toDate", to != null ? to.toString() : null);
            if (accountId != null) {
                extra.put("accountId", accountId.toString());
            }
            auditLogService.recordInNewTransaction(new AuditCommand(AuditAction.REPORT_VIEWED,
                    AuditResourceType.REPORT, primaryOrg, userId, null,
                    "Report viewed: " + reportType,
                    null, null,
                    auditLogService.toJson(AuditSnapshots.metadata("API", extra)), true));
        } catch (Exception ignored) {
            // Reporting must never fail because auditing failed.
        }
    }

    @Transactional(readOnly = true)
    public ExpenseSummaryResponse getExpenseSummary(UUID userId, LocalDate from, LocalDate to, UUID vendorId) {
        validateDateRange(from, to);
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (vendorId != null) {
            validateVendorInOrg(orgIds, vendorId);
        }
        if (orgIds.isEmpty()) {
            return new ExpenseSummaryResponse(from, to, List.of(), List.of(), BigDecimal.ZERO, 0);
        }
        var byAccount = reportRepository.expensesByAccount(orgIds, from, to, vendorId);
        var byVendor = reportRepository.expensesByVendor(orgIds, from, to, vendorId);
        BigDecimal total = byAccount.stream().map(b -> nullToZero(b.totalAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long count = byAccount.stream().mapToLong(b -> b.expenseCount()).sum();
        return new ExpenseSummaryResponse(from, to, byAccount, byVendor, total, count);
    }

    private void validateVendorInOrg(List<UUID> orgIds, UUID vendorId) {
        var vendor = vendorRepository.findById(vendorId)
                .orElseThrow(() -> new VendorNotFoundException(vendorId));
        if (!orgIds.contains(vendor.getOrganization().getId())) {
            throw new VendorNotFoundException(vendorId);
        }
    }

    @Transactional(readOnly = true)
    public InvoiceSummaryResponse getInvoiceSummary(UUID userId, LocalDate from, LocalDate to, UUID customerId) {
        validateDateRange(from, to);
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (customerId != null) {
            var customer = customerRepository.findById(customerId)
                    .orElseThrow(() -> new CustomerNotFoundException(customerId));
            if (!orgIds.contains(customer.getOrganization().getId())) {
                throw new CustomerNotFoundException(customerId);
            }
        }
        if (orgIds.isEmpty()) {
            return new InvoiceSummaryResponse(from, to, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Object[] agg = reportRepository.invoiceAggregates(orgIds, from, to, REPORTABLE_INVOICE_STATUSES, customerId);
        long count = agg[0] == null ? 0 : (Long) agg[0];
        // amountPaid stays zero until payment processing exists; report stored values as-is.
        return new InvoiceSummaryResponse(from, to, count,
                nullToZero((BigDecimal) agg[1]), nullToZero((BigDecimal) agg[2]), nullToZero((BigDecimal) agg[3]));
    }

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(UUID userId, LocalDate from, LocalDate to) {
        validateDateRange(from, to);
        List<UUID> orgIds = accessibleOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            return new DashboardResponse(from, to, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0);
        }
        ProfitAndLossResponse pnl = getProfitAndLoss(userId, from, to);
        InvoiceSummaryResponse inv = getInvoiceSummary(userId, from, to, null);
        return new DashboardResponse(from, to,
                pnl.totalRevenue(), pnl.totalExpenses(), pnl.netProfit(),
                inv.totalInvoiced(), inv.totalOutstanding(),
                reportRepository.countActiveCustomers(orgIds),
                reportRepository.countActiveVendors(orgIds),
                inv.invoiceCount(),
                reportRepository.countRecordedExpenses(orgIds, from, to));
    }
}
