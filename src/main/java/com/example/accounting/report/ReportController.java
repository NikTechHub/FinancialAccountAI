package com.example.accounting.report;

import com.example.accounting.report.dto.BalanceSheetResponse;
import com.example.accounting.report.dto.DashboardResponse;
import com.example.accounting.report.dto.ExpenseSummaryResponse;
import com.example.accounting.report.dto.GeneralLedgerLine;
import com.example.accounting.report.dto.InvoiceSummaryResponse;
import com.example.accounting.report.dto.ProfitAndLossResponse;
import com.example.accounting.report.dto.TrialBalanceResponse;
import com.example.accounting.security.AuthContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read-only Reports &amp; Analytics API. Every endpoint requires
 * authentication; any authenticated organization member (OWNER, ADMIN,
 * ACCOUNTANT, EMPLOYEE, VIEWER) may read. All figures derive from posted
 * accounting data scoped to the caller's organizations.
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/trial-balance")
    public ResponseEntity<TrialBalanceResponse> trialBalance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        UUID userId = AuthContext.currentUserId();
        return ResponseEntity.ok(reportService.getTrialBalance(userId, fromDate, toDate));
    }

    @GetMapping("/general-ledger")
    public ResponseEntity<Page<GeneralLedgerLine>> generalLedger(
            @RequestParam UUID accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID userId = AuthContext.currentUserId();
        return ResponseEntity.ok(reportService.getGeneralLedger(userId, accountId, fromDate, toDate, pageable));
    }

    @GetMapping("/profit-and-loss")
    public ResponseEntity<ProfitAndLossResponse> profitAndLoss(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        UUID userId = AuthContext.currentUserId();
        return ResponseEntity.ok(reportService.getProfitAndLoss(userId, fromDate, toDate));
    }

    @GetMapping("/balance-sheet")
    public ResponseEntity<BalanceSheetResponse> balanceSheet(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        UUID userId = AuthContext.currentUserId();
        return ResponseEntity.ok(reportService.getBalanceSheet(userId, asOfDate));
    }

    @GetMapping("/expenses")
    public ResponseEntity<ExpenseSummaryResponse> expenses(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) UUID vendorId) {
        UUID userId = AuthContext.currentUserId();
        return ResponseEntity.ok(reportService.getExpenseSummary(userId, fromDate, toDate, vendorId));
    }

    @GetMapping("/invoices")
    public ResponseEntity<InvoiceSummaryResponse> invoices(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) UUID customerId) {
        UUID userId = AuthContext.currentUserId();
        return ResponseEntity.ok(reportService.getInvoiceSummary(userId, fromDate, toDate, customerId));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardResponse> dashboard(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        UUID userId = AuthContext.currentUserId();
        return ResponseEntity.ok(reportService.getDashboard(userId, fromDate, toDate));
    }
}
