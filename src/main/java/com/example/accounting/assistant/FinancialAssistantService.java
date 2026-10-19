package com.example.accounting.assistant;

import com.example.accounting.assistant.dto.AssistantChatResponse;
import com.example.accounting.assistant.dto.AssistantPeriod;
import com.example.accounting.audit.AuditAction;
import com.example.accounting.audit.AuditCommand;
import com.example.accounting.audit.AuditLogService;
import com.example.accounting.audit.AuditResourceType;
import com.example.accounting.audit.AuditSnapshots;
import com.example.accounting.entity.Account;
import com.example.accounting.entity.InvoiceStatus;
import com.example.accounting.entity.Vendor;
import com.example.accounting.exception.OrganizationNotFoundException;
import com.example.accounting.report.CustomerOutstanding;
import com.example.accounting.report.ReportRepository;
import com.example.accounting.report.ReportService;
import com.example.accounting.report.dto.DashboardResponse;
import com.example.accounting.report.dto.ExpenseSummaryResponse;
import com.example.accounting.report.dto.GeneralLedgerLine;
import com.example.accounting.report.dto.InvoiceSummaryResponse;
import com.example.accounting.report.dto.ProfitAndLossResponse;
import com.example.accounting.report.dto.TrialBalanceResponse;
import com.example.accounting.repository.AccountRepository;
import com.example.accounting.repository.OrganizationMembershipRepository;
import com.example.accounting.repository.OrganizationRepository;
import com.example.accounting.repository.VendorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Read-only AI financial assistant orchestration.
 *
 * <p>Flow: authenticate (controller) → resolve orgs from membership →
 * classify intent (deterministic allowlist) → resolve dates (deterministic)
 * → fetch figures from {@link ReportService} → ask the AI layer to explain
 * → validate every number in the AI text against the deterministic figures →
 * fall back to a template answer on any failure or mismatch.</p>
 *
 * <p>The LLM never sees or supplies an organization ID, never performs
 * calculations, and has no write capability: there is no tool/function
 * calling at all — dispatch to reporting services happens in this class
 * with server-side organization context.</p>
 */
@Service
public class FinancialAssistantService {

    private static final Logger log = LoggerFactory.getLogger(FinancialAssistantService.class);

    private static final List<InvoiceStatus> REPORTABLE_INVOICE_STATUSES = List.of(
            InvoiceStatus.ISSUED, InvoiceStatus.PARTIALLY_PAID, InvoiceStatus.PAID, InvoiceStatus.OVERDUE);

    private static final Pattern NUMBER_TOKEN = Pattern.compile("(?<!\\d)(\\d[\\d,]*(?:\\.\\d+)?)");
    private static final Pattern ACCOUNT_CODE_TOKEN = Pattern.compile("\\b(\\d{3,10})\\b");
    private static final List<String> COMPARISON_KEYWORDS =
            List.of("compar", " vs ", "versus", "previous", "change", "increas", "decreas", "growth", "trend");

    private final ReportService reportService;
    private final ReportRepository reportRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationRepository organizationRepository;
    private final AccountRepository accountRepository;
    private final VendorRepository vendorRepository;
    private final IntentClassifier intentClassifier;
    private final DateResolver dateResolver;
    private final AssistantAiGenerator aiGenerator;
    private final Clock clock;
    private final AuditLogService auditLogService;

    public FinancialAssistantService(ReportService reportService,
                                     ReportRepository reportRepository,
                                     OrganizationMembershipRepository membershipRepository,
                                     OrganizationRepository organizationRepository,
                                     AccountRepository accountRepository,
                                     VendorRepository vendorRepository,
                                     IntentClassifier intentClassifier,
                                     DateResolver dateResolver,
                                     AssistantAiGenerator aiGenerator,
                                     Clock assistantClock,
                                     AuditLogService auditLogService) {
        this.reportService = reportService;
        this.reportRepository = reportRepository;
        this.membershipRepository = membershipRepository;
        this.organizationRepository = organizationRepository;
        this.accountRepository = accountRepository;
        this.vendorRepository = vendorRepository;
        this.intentClassifier = intentClassifier;
        this.dateResolver = dateResolver;
        this.aiGenerator = aiGenerator;
        this.clock = assistantClock;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public AssistantChatResponse chat(UUID userId, String message) {
        long startNanos = System.nanoTime();
        AssistantIntent intent = intentClassifier.classify(message);
        boolean success = true;
        AssistantChatResponse response;
        List<UUID> orgIdsForAudit = List.of();
        AssistantPeriod periodForAudit = null;
        List<String> sourcesForAudit = List.of();
        try {
            List<UUID> orgIds = accessibleOrganizationIds(userId);
            orgIdsForAudit = orgIds;
            if (orgIds.isEmpty()) {
                throw new OrganizationNotFoundException(UUID.randomUUID());
            }
            response = switch (intent) {
                case UNSUPPORTED -> unsupported();
                case CLARIFICATION -> clarifyPeriod();
                case REVENUE, EXPENSES, PROFIT, DASHBOARD, TRIAL_BALANCE, INVOICES,
                        VENDOR_EXPENSES -> handlePeriodIntent(userId, orgIds, message, intent);
                case BALANCE_SHEET -> handleBalanceSheet(userId, orgIds, message);
                case GENERAL_LEDGER -> handleGeneralLedger(userId, orgIds, message);
            };
            periodForAudit = response.period();
            sourcesForAudit = response.sources() != null ? response.sources() : List.of();
            return response;
        } catch (RuntimeException e) {
            // Domain exceptions (404/400) propagate to the global handler.
            success = false;
            throw e;
        } finally {
            // Safe metadata only: never the message, figures, or payloads.
            log.info("assistant chat intent={} durationMs={} success={}",
                    intent, (System.nanoTime() - startNanos) / 1_000_000, success);
            auditAssistantQuery(userId, orgIdsForAudit, intent, periodForAudit, sourcesForAudit, success);
        }
    }

    private List<UUID> accessibleOrganizationIds(UUID userId) {
        return membershipRepository.findByUserId(userId).stream()
                .map(m -> m.getOrganization().getId())
                .collect(Collectors.toList());
    }

    private String organizationCurrency(List<UUID> orgIds) {
        return organizationRepository.findById(orgIds.get(0))
                .map(org -> org.getCurrency() == null ? "" : org.getCurrency().trim())
                .orElse("");
    }

    // ------------------------------------------------------------------
    // Period-based intents
    // ------------------------------------------------------------------

    private AssistantChatResponse handlePeriodIntent(UUID userId, List<UUID> orgIds,
                                                     String message, AssistantIntent intent) {
        LocalDate today = LocalDate.now(clock);
        Optional<ResolvedPeriod> period = dateResolver.resolve(message, today);
        if (period.isEmpty()) {
            return clarifyPeriod();
        }
        ResolvedPeriod current = period.get();
        boolean compare = wantsComparison(message)
                && (intent == AssistantIntent.REVENUE || intent == AssistantIntent.EXPENSES
                || intent == AssistantIntent.PROFIT);
        ResolvedPeriod previous = compare ? previousPeriod(current) : null;

        GroundedAnswer grounded = buildGrounded(userId, orgIds, message, intent, current, previous);
        return answerWithAi(message, grounded);
    }

    private AssistantChatResponse handleBalanceSheet(UUID userId, List<UUID> orgIds, String message) {
        LocalDate today = LocalDate.now(clock);
        // Point-in-time question: an explicit "as of" date wins, otherwise the
        // resolved period end, otherwise today. The chosen date is exposed.
        LocalDate asOf = dateResolver.resolveAsOf(message, today).orElse(today);
        var sheet = reportService.getBalanceSheet(userId, asOf);
        String currency = organizationCurrency(orgIds);
        StringBuilder data = new StringBuilder();
        data.append("Organization currency: ").append(currency.isEmpty() ? "not set" : currency).append(".\n");
        data.append("Balance sheet as of ").append(asOf).append(".\n");
        data.append("totalAssets=").append(num(sheet.totalAssets())).append(", ");
        data.append("totalLiabilities=").append(num(sheet.totalLiabilities())).append(", ");
        data.append("totalEquity=").append(num(sheet.totalEquity())).append(".\n");
        appendBalanceLines(data, "Assets", sheet.assetLines().stream()
                .map(l -> l.accountCode() + " " + l.accountName() + "=" + num(l.balance())).toList());
        appendBalanceLines(data, "Liabilities", sheet.liabilityLines().stream()
                .map(l -> l.accountCode() + " " + l.accountName() + "=" + num(l.balance())).toList());
        appendBalanceLines(data, "Equity", sheet.equityLines().stream()
                .map(l -> l.accountCode() + " " + l.accountName() + "=" + num(l.balance())).toList());

        String fallback = "As of " + asOf + ": assets " + money(sheet.totalAssets(), currency)
                + ", liabilities " + money(sheet.totalLiabilities(), currency)
                + ", equity " + money(sheet.totalEquity(), currency) + ".";
        GroundedAnswer grounded = new GroundedAnswer(data.toString(), fallback,
                new AssistantPeriod(null, asOf), List.of("balance-sheet"));
        return answerWithAi(message, grounded);
    }

    private AssistantChatResponse handleGeneralLedger(UUID userId, List<UUID> orgIds, String message) {
        Optional<Account> account = resolveAccount(orgIds, message);
        if (account.isEmpty()) {
            return new AssistantChatResponse(
                    "Which account should I look at? Please give me an account code (for example 1100) "
                            + "or an account name such as Cash or Accounts Receivable.",
                    false, null, List.of());
        }
        LocalDate today = LocalDate.now(clock);
        Optional<ResolvedPeriod> period = dateResolver.resolve(message, today);
        if (period.isEmpty()) {
            return clarifyPeriod();
        }
        ResolvedPeriod current = period.get();
        Account acc = account.get();
        Page<GeneralLedgerLine> page = reportService.getGeneralLedger(
                userId, acc.getId(), current.fromDate(), current.toDate(), PageRequest.of(0, 20));
        BigDecimal debits = page.getContent().stream().map(GeneralLedgerLine::debitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = page.getContent().stream().map(GeneralLedgerLine::creditAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String currency = organizationCurrency(orgIds);
        StringBuilder data = new StringBuilder();
        data.append("General ledger for account ").append(acc.getCode()).append(" (")
                .append(acc.getName()).append(") from ").append(current.fromDate())
                .append(" to ").append(current.toDate()).append(".\n");
        data.append("lineCount=").append(page.getTotalElements())
                .append(", shownLines=").append(page.getContent().size())
                .append(", debitTotal=").append(num(debits))
                .append(", creditTotal=").append(num(credits)).append(".\n");
        for (GeneralLedgerLine line : page.getContent()) {
            data.append(line.entryDate()).append(" ").append(line.entryNumber())
                    .append(" debit=").append(num(line.debitAmount()))
                    .append(" credit=").append(num(line.creditAmount())).append(".\n");
        }
        String fallback = "Account " + acc.getCode() + " (" + acc.getName() + ") had "
                + page.getTotalElements() + " posted lines from " + current.fromDate()
                + " to " + current.toDate() + ": debits " + money(debits, currency)
                + ", credits " + money(credits, currency) + ".";
        GroundedAnswer grounded = new GroundedAnswer(data.toString(), fallback,
                new AssistantPeriod(current.fromDate(), current.toDate()), List.of("general-ledger"));
        return answerWithAi(message, grounded);
    }

    // ------------------------------------------------------------------
    // Grounded data builders (deterministic; reuse reporting services)
    // ------------------------------------------------------------------

    private record GroundedAnswer(String data, String fallback, AssistantPeriod period, List<String> sources) {
    }

    private GroundedAnswer buildGrounded(UUID userId, List<UUID> orgIds, String message,
                                         AssistantIntent intent, ResolvedPeriod current, ResolvedPeriod previous) {
        String currency = organizationCurrency(orgIds);
        return switch (intent) {
            case REVENUE, PROFIT -> {
                ProfitAndLossResponse pnl = reportService.getProfitAndLoss(
                        userId, current.fromDate(), current.toDate());
                StringBuilder data = new StringBuilder(periodHeader(currency, current));
                data.append("totalRevenue=").append(num(pnl.totalRevenue()))
                        .append(", totalExpenses=").append(num(pnl.totalExpenses()))
                        .append(", netProfit=").append(num(pnl.netProfit())).append(".\n");
                for (var line : pnl.revenueLines()) {
                    data.append("Revenue ").append(line.accountCode()).append(" ")
                            .append(line.accountName()).append("=").append(num(line.amount())).append(".\n");
                }
                for (var line : pnl.expenseLines()) {
                    data.append("Expense ").append(line.accountCode()).append(" ")
                            .append(line.accountName()).append("=").append(num(line.amount())).append(".\n");
                }
                String comparison = "";
                if (previous != null) {
                    ProfitAndLossResponse prev = reportService.getProfitAndLoss(
                            userId, previous.fromDate(), previous.toDate());
                    comparison = comparisonText("revenue", pnl.totalRevenue(), prev.totalRevenue(), previous)
                            + " " + comparisonText("expenses", pnl.totalExpenses(), prev.totalExpenses(), previous);
                    data.append("Previous period ").append(previous.fromDate()).append(" to ")
                            .append(previous.toDate()).append(": totalRevenue=")
                            .append(num(prev.totalRevenue())).append(", totalExpenses=")
                            .append(num(prev.totalExpenses())).append(", netProfit=")
                            .append(num(prev.netProfit())).append(".\n");
                    data.append(comparison).append("\n");
                }
                String fallback;
                if (intent == AssistantIntent.REVENUE) {
                    fallback = "Revenue for " + current.label() + " (" + current.fromDate()
                            + " to " + current.toDate() + "): " + money(pnl.totalRevenue(), currency) + "."
                            + (comparison.isEmpty() ? "" : " " + comparison);
                } else {
                    fallback = "Net profit for " + current.label() + " (" + current.fromDate()
                            + " to " + current.toDate() + "): " + money(pnl.netProfit(), currency)
                            + " (revenue " + money(pnl.totalRevenue(), currency)
                            + ", expenses " + money(pnl.totalExpenses(), currency) + ")."
                            + (comparison.isEmpty() ? "" : " " + comparison);
                }
                yield new GroundedAnswer(data.toString(), fallback,
                        new AssistantPeriod(current.fromDate(), current.toDate()),
                        List.of("profit-and-loss"));
            }
            case EXPENSES -> {
                ExpenseSummaryResponse summary = reportService.getExpenseSummary(
                        userId, current.fromDate(), current.toDate(), null);
                StringBuilder data = new StringBuilder(periodHeader(currency, current));
                data.append("totalExpenses=").append(num(summary.totalAmount()))
                        .append(", expenseCount=").append(summary.expenseCount()).append(".\n");
                for (var line : summary.byAccount()) {
                    data.append("Expense account ").append(line.expenseAccountCode()).append(" ")
                            .append(line.expenseAccountName()).append("=").append(num(line.totalAmount()))
                            .append(" (").append(line.expenseCount()).append(" expenses).\n");
                }
                String comparison = "";
                if (previous != null) {
                    ExpenseSummaryResponse prev = reportService.getExpenseSummary(
                            userId, previous.fromDate(), previous.toDate(), null);
                    comparison = comparisonText(
                            "expenses", summary.totalAmount(), prev.totalAmount(), previous);
                    data.append("Previous period ").append(previous.fromDate()).append(" to ")
                            .append(previous.toDate()).append(": totalExpenses=")
                            .append(num(prev.totalAmount())).append(".\n");
                    data.append(comparison).append("\n");
                }
                String top = summary.byAccount().stream()
                        .sorted(Comparator.comparing(
                                (com.example.accounting.report.dto.ExpenseAccountSummaryLine l) -> l.totalAmount())
                                .reversed())
                        .limit(3)
                        .map(l -> l.expenseAccountName() + " " + money(l.totalAmount(), currency))
                        .collect(Collectors.joining("; "));
                String fallback = "Expenses for " + current.label() + " (" + current.fromDate()
                        + " to " + current.toDate() + "): " + money(summary.totalAmount(), currency)
                        + (top.isEmpty() ? "." : ". Largest categories: " + top + ".")
                        + (comparison.isEmpty() ? "" : " " + comparison);
                yield new GroundedAnswer(data.toString(), fallback,
                        new AssistantPeriod(current.fromDate(), current.toDate()), List.of("expenses"));
            }
            case TRIAL_BALANCE -> {
                TrialBalanceResponse tb = reportService.getTrialBalance(
                        userId, current.fromDate(), current.toDate());
                StringBuilder data = new StringBuilder(periodHeader(currency, current));
                data.append("totalDebits=").append(num(tb.totalDebits()))
                        .append(", totalCredits=").append(num(tb.totalCredits()))
                        .append(", totalDifference=").append(num(tb.totalDifference())).append(".\n");
                for (var line : tb.lines()) {
                    data.append(line.accountCode()).append(" ").append(line.accountName())
                            .append(" (").append(line.accountType()).append(") debit=")
                            .append(num(line.debitTotal())).append(" credit=")
                            .append(num(line.creditTotal())).append(" net=")
                            .append(num(line.netBalance())).append(".\n");
                }
                String fallback = "Trial balance for " + current.label() + ": total debits "
                        + money(tb.totalDebits(), currency) + ", total credits "
                        + money(tb.totalCredits(), currency) + ", difference "
                        + money(tb.totalDifference(), currency) + " across " + tb.lines().size() + " accounts.";
                yield new GroundedAnswer(data.toString(), fallback,
                        new AssistantPeriod(current.fromDate(), current.toDate()), List.of("trial-balance"));
            }
            case INVOICES -> {
                InvoiceSummaryResponse inv = reportService.getInvoiceSummary(
                        userId, current.fromDate(), current.toDate(), null);
                Object[] overdueAgg = reportRepository.invoiceAggregates(orgIds,
                        current.fromDate(), current.toDate(), List.of(InvoiceStatus.OVERDUE), null);
                long overdueCount = overdueAgg[0] == null ? 0 : (Long) overdueAgg[0];
                BigDecimal overdueAmount = overdueAgg[3] == null
                        ? BigDecimal.ZERO : (BigDecimal) overdueAgg[3];
                List<CustomerOutstanding> perCustomer = reportRepository.invoiceOutstandingByCustomer(
                        orgIds, current.fromDate(), current.toDate(), REPORTABLE_INVOICE_STATUSES);
                StringBuilder data = new StringBuilder(periodHeader(currency, current));
                data.append("invoiceCount=").append(inv.invoiceCount())
                        .append(", totalInvoiced=").append(num(inv.totalInvoiced()))
                        .append(", totalPaid=").append(num(inv.totalPaid()))
                        .append(", totalOutstanding=").append(num(inv.totalOutstanding())).append(".\n");
                data.append("overdueCount=").append(overdueCount)
                        .append(", overdueOutstanding=").append(num(overdueAmount)).append(".\n");
                for (CustomerOutstanding c : perCustomer) {
                    data.append("Customer ").append(c.customerName()).append(": invoices=")
                            .append(c.invoiceCount()).append(" invoiced=").append(num(c.totalInvoiced()))
                            .append(" outstanding=").append(num(c.totalOutstanding())).append(".\n");
                }
                String fallback = "Invoiced " + money(inv.totalInvoiced(), currency) + " across "
                        + inv.invoiceCount() + " invoices (" + current.fromDate() + " to " + current.toDate()
                        + "); paid " + money(inv.totalPaid(), currency) + ", outstanding "
                        + money(inv.totalOutstanding(), currency) + ". Overdue invoices: " + overdueCount + ".";
                yield new GroundedAnswer(data.toString(), fallback,
                        new AssistantPeriod(current.fromDate(), current.toDate()), List.of("invoices"));
            }
            case VENDOR_EXPENSES -> {
                Optional<Vendor> named = resolveVendor(orgIds, message);
                ExpenseSummaryResponse summary = reportService.getExpenseSummary(userId,
                        current.fromDate(), current.toDate(),
                        named.map(Vendor::getId).orElse(null));
                StringBuilder data = new StringBuilder(periodHeader(currency, current));
                data.append("totalExpenses=").append(num(summary.totalAmount())).append(".\n");
                for (var line : summary.byVendor()) {
                    data.append("Vendor ").append(line.vendorName()).append("=")
                            .append(num(line.totalAmount())).append(" (")
                            .append(line.expenseCount()).append(" expenses).\n");
                }
                String fallback;
                if (named.isPresent()) {
                    fallback = "Spending with " + named.get().getName() + " for " + current.label()
                            + ": " + money(summary.totalAmount(), currency) + ".";
                } else {
                    String top = summary.byVendor().stream()
                            .sorted(Comparator.comparing(
                                    (com.example.accounting.report.dto.VendorExpenseSummaryLine l)
                                            -> l.totalAmount()).reversed())
                            .limit(3)
                            .map(l -> l.vendorName() + " " + money(l.totalAmount(), currency))
                            .collect(Collectors.joining("; "));
                    fallback = "Vendor-attributed expenses for " + current.label() + ": "
                            + money(summary.totalAmount(), currency)
                            + (top.isEmpty() ? "." : ". Top vendors: " + top + ".");
                }
                yield new GroundedAnswer(data.toString(), fallback,
                        new AssistantPeriod(current.fromDate(), current.toDate()), List.of("expenses"));
            }
            case DASHBOARD -> {
                DashboardResponse dash = reportService.getDashboard(
                        userId, current.fromDate(), current.toDate());
                StringBuilder data = new StringBuilder(periodHeader(currency, current));
                data.append("totalRevenue=").append(num(dash.totalRevenue()))
                        .append(", totalExpenses=").append(num(dash.totalExpenses()))
                        .append(", netProfit=").append(num(dash.netProfit())).append(".\n");
                data.append("totalInvoiced=").append(num(dash.totalInvoiced()))
                        .append(", totalOutstanding=").append(num(dash.totalOutstanding())).append(".\n");
                data.append("customerCount=").append(dash.customerCount())
                        .append(", vendorCount=").append(dash.vendorCount())
                        .append(", invoiceCount=").append(dash.invoiceCount())
                        .append(", expenseCount=").append(dash.expenseCount()).append(".\n");
                String fallback = "Summary for " + current.label() + ": revenue "
                        + money(dash.totalRevenue(), currency) + ", expenses "
                        + money(dash.totalExpenses(), currency) + ", net profit "
                        + money(dash.netProfit(), currency) + "; invoiced "
                        + money(dash.totalInvoiced(), currency) + " with "
                        + money(dash.totalOutstanding(), currency) + " outstanding; "
                        + dash.customerCount() + " customers, " + dash.vendorCount() + " vendors, "
                        + dash.invoiceCount() + " invoices, " + dash.expenseCount() + " expenses.";
                yield new GroundedAnswer(data.toString(), fallback,
                        new AssistantPeriod(current.fromDate(), current.toDate()), List.of("dashboard"));
            }
            default -> throw new IllegalStateException("Unhandled intent: " + intent);
        };
    }

    // ------------------------------------------------------------------
    // AI invocation + hallucination guard
    // ------------------------------------------------------------------

    private AssistantChatResponse answerWithAi(String message, GroundedAnswer grounded) {
        try {
            String aiAnswer = aiGenerator.generateAnswer(message, grounded.data());
            if (aiAnswer == null || aiAnswer.isBlank()) {
                return fallback(grounded);
            }
            if (!numbersSupported(aiAnswer, message, grounded.data())) {
                log.warn("assistant AI answer contained unsupported numbers; using deterministic fallback");
                return fallback(grounded);
            }
            return new AssistantChatResponse(aiAnswer.strip(), true, grounded.period(), grounded.sources());
        } catch (Exception e) {
            // Model unavailable or failed: deterministic figures still answer.
            log.warn("assistant AI generation failed; using deterministic fallback");
            return fallback(grounded);
        }
    }

    private static AssistantChatResponse fallback(GroundedAnswer grounded) {
        return new AssistantChatResponse(grounded.fallback(), true, grounded.period(), grounded.sources());
    }

    /**
     * Every numeric token in the AI answer must already appear in the
     * deterministic data, the user message, or the resolved period.
     * Comparison is numeric (so 1000 and 1000.00 match) after stripping
     * thousands separators.
     */
    static boolean numbersSupported(String aiAnswer, String userMessage, String groundedData) {
        List<BigDecimal> allowed = extractNumbers(userMessage + "\n" + groundedData);
        for (BigDecimal candidate : extractNumbers(aiAnswer)) {
            boolean found = false;
            for (BigDecimal ok : allowed) {
                if (ok.compareTo(candidate) == 0) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    static List<BigDecimal> extractNumbers(String text) {
        List<BigDecimal> numbers = new ArrayList<>();
        if (text == null) {
            return numbers;
        }
        Matcher matcher = NUMBER_TOKEN.matcher(text);
        while (matcher.find()) {
            try {
                numbers.add(new BigDecimal(matcher.group(1).replace(",", "")));
            } catch (NumberFormatException ignored) {
                // skip non-numeric tokens
            }
        }
        return numbers;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static AssistantChatResponse unsupported() {
        return new AssistantChatResponse(
                "I can't perform that action. I'm a read-only assistant: I can answer questions "
                        + "about your recorded revenue, expenses, profit, invoices, vendors, accounts "
                        + "and balance sheet, but I can't create, change, pay, approve or delete anything.",
                false, null, List.of());
    }

    private static AssistantChatResponse clarifyPeriod() {
        return new AssistantChatResponse(
                "What period would you like me to use\u2014this month, last month, this year, "
                        + "or a specific date range?",
                false, null, List.of());
    }

    private static boolean wantsComparison(String message) {
        String text = message.toLowerCase();
        return COMPARISON_KEYWORDS.stream().anyMatch(text::contains);
    }

    private static ResolvedPeriod previousPeriod(ResolvedPeriod current) {
        long days = current.lengthInDays();
        LocalDate prevTo = current.fromDate().minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days - 1);
        return new ResolvedPeriod(prevFrom, prevTo, "previous period");
    }

    private static String comparisonText(String metric, BigDecimal current, BigDecimal previous,
                                         ResolvedPeriod previousPeriod) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return "Change in " + metric + " is not computable because the previous period ("
                    + previousPeriod.fromDate() + " to " + previousPeriod.toDate() + ") was zero.";
        }
        BigDecimal pct = current.subtract(previous).divide(previous.abs(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
        String direction = current.compareTo(previous) >= 0 ? "+" : "";
        return "Compared with " + previousPeriod.fromDate() + " to " + previousPeriod.toDate()
                + ", " + metric + " changed by " + direction + pct.toPlainString() + "%.";
    }

    private Optional<Account> resolveAccount(List<UUID> orgIds, String message) {
        List<Account> accounts =
                accountRepository.findByOrganizationIdInAndActiveTrueOrderByCodeAsc(orgIds);
        if (accounts.isEmpty()) {
            return Optional.empty();
        }
        Matcher codeMatcher = ACCOUNT_CODE_TOKEN.matcher(message);
        while (codeMatcher.find()) {
            String code = codeMatcher.group(1);
            for (Account account : accounts) {
                if (account.getCode().equalsIgnoreCase(code)) {
                    return Optional.of(account);
                }
            }
        }
        String text = message.toLowerCase();
        List<Account> nameMatches = accounts.stream()
                .filter(a -> text.contains(a.getName().toLowerCase()))
                .toList();
        if (nameMatches.size() == 1) {
            return Optional.of(nameMatches.get(0));
        }
        return Optional.empty();
    }

    private Optional<Vendor> resolveVendor(List<UUID> orgIds, String message) {
        String text = message.toLowerCase();
        List<Vendor> matches = new ArrayList<>();
        for (UUID orgId : orgIds) {
            for (Vendor vendor : vendorRepository.findByOrganizationId(orgId)) {
                if (Boolean.FALSE.equals(vendor.getActive())) {
                    continue;
                }
                if (vendor.getName() != null && text.contains(vendor.getName().toLowerCase())) {
                    matches.add(vendor);
                }
            }
        }
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private static String periodHeader(String currency, ResolvedPeriod current) {
        return "Organization currency: " + (currency.isEmpty() ? "not set" : currency) + ".\n"
                + "Period: " + current.label() + " " + current.fromDate() + " to " + current.toDate() + ".\n";
    }

    private static void appendBalanceLines(StringBuilder data, String section, List<String> lines) {
        data.append(section).append(": ");
        data.append(lines.isEmpty() ? "none" : String.join("; ", lines)).append(".\n");
    }

    private static String num(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal value, String currency) {
        String amount = num(value);
        return currency.isEmpty() ? amount : currency + " " + amount;
    }

    /**
     * Assistant-query auditing in a separate transaction so the record survives
     * the read-only chat transaction. Metadata carries only intent, source
     * report types, resolved period and success — never the user question,
     * AI answer, figures or prompts.
     */
    private void auditAssistantQuery(UUID userId, List<UUID> orgIds, AssistantIntent intent,
                                     AssistantPeriod period, List<String> sources, boolean success) {
        if (orgIds == null || orgIds.isEmpty()) {
            return;
        }
        try {
            java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
            extra.put("intent", intent != null ? intent.name() : "unknown");
            extra.put("sources", sources != null ? sources : List.of());
            extra.put("fromDate", period != null && period.fromDate() != null
                    ? period.fromDate().toString() : null);
            extra.put("toDate", period != null && period.toDate() != null
                    ? period.toDate().toString() : null);
            auditLogService.recordInNewTransaction(new AuditCommand(AuditAction.ASSISTANT_QUERY,
                    AuditResourceType.ASSISTANT, orgIds.get(0), userId, null,
                    "Assistant query: " + (intent != null ? intent.name() : "unknown"),
                    null, null,
                    auditLogService.toJson(AuditSnapshots.metadata("API", extra)), success));
        } catch (Exception ignored) {
            // Assistant answers must never fail because auditing failed.
        }
    }
}
