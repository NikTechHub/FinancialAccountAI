package com.example.accounting.report;

import com.example.accounting.dto.RegisterRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves reports never leak across organizations even when both orgs use
 * identical account codes/names, invoice numbers and vendor/customer names.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportTenantIsolationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private TenantSetup orgA;
    private TenantSetup orgB;

    private static final class TenantSetup {
        String token;
        String cashId;
        String revenueId;
        String expenseId;
        String vendorId;
        String customerId;
    }

    @BeforeEach
    void setUp() throws Exception {
        orgA = setUpTenant("isoA");
        orgB = setUpTenant("isoB");

        // Identical chart of accounts, different posted amounts.
        // Org A: Dr Cash 1000 / Cr Revenue 1000.
        postJournal(orgA, "JE-1", "2026-02-01", orgA.cashId, orgA.revenueId, "1000");
        // Org B: Dr Cash 5000 / Cr Revenue 5000.
        postJournal(orgB, "JE-1", "2026-02-01", orgB.cashId, orgB.revenueId, "5000");

        // Identical vendor/customer names in both orgs.
        // Recorded expenses: A 100, B 300.
        recordExpense(orgA, "100", "2026-03-01");
        recordExpense(orgB, "300", "2026-03-01");

        // Identical invoice numbers, different quantities: A 1x500, B 3x500
        // (10% tax, no discount -> A 550, B 1650).
        issueInvoice(orgA, "INV-001", 1);
        issueInvoice(orgB, "INV-001", 3);
    }

    private TenantSetup setUpTenant(String prefix) throws Exception {
        TenantSetup t = new TenantSetup();
        String suffix = prefix + "-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Iso", "Owner",
                "iso." + suffix + "@example.com",
                "SecurePass123",
                "IsoOrg-" + suffix,
                "Legal " + suffix,
                "PRIVATE_LIMITED",
                "India", "INR", "Asia/Kolkata"
        );
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        t.token = objectMapper.readTree(reg.getResponse().getContentAsString()).get("accessToken").asText();
        t.cashId = createAccount(t, "1000", "Cash", "ASSET");
        t.revenueId = createAccount(t, "4000", "Service Revenue", "REVENUE");
        t.expenseId = createAccount(t, "5000", "Office Expense", "EXPENSE");
        t.vendorId = createVendor(t, "Shared Vendor");
        t.customerId = createCustomer(t, "Shared Customer");
        return t;
    }

    private String bearer(TenantSetup t) {
        return "Bearer " + t.token;
    }

    private String createAccount(TenantSetup t, String code, String name, String type) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer(t))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"%s","type":"%s"}
                                """.formatted(code, name, type)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createVendor(TenantSetup t, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", bearer(t))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createCustomer(TenantSetup t, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", bearer(t))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void postJournal(TenantSetup t, String entryNumber, String date,
                             String debitAccountId, String creditAccountId, String amount) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", bearer(t))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entryNumber":"%s","entryDate":"%s",
                                 "lines":[{"accountId":"%s","debitAmount":%s,"creditAmount":0},
                                          {"accountId":"%s","debitAmount":0,"creditAmount":%s}]}
                                """.formatted(entryNumber, date, debitAccountId, amount, creditAccountId, amount)))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/v1/journal-entries/{id}/post", id)
                        .header("Authorization", bearer(t)))
                .andExpect(status().isOk());
    }

    private void recordExpense(TenantSetup t, String amount, String date) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", bearer(t))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vendorId":"%s","expenseAccountId":"%s","paymentAccountId":"%s",
                                 "amount":%s,"expenseDate":"%s"}
                                """.formatted(t.vendorId, t.expenseId, t.cashId, amount, date)))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/v1/expenses/{id}/record", id)
                        .header("Authorization", bearer(t)))
                .andExpect(status().isOk());
    }

    private void issueInvoice(TenantSetup t, String number, int quantity) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer(t))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s","invoiceNumber":"%s","invoiceDate":"2026-04-01",
                                 "lines":[{"description":"Service","quantity":%d,"unitPrice":500,
                                           "taxRate":10,"discountAmount":0,"revenueAccountId":"%s"}]}
                                """.formatted(t.customerId, number, quantity, t.revenueId)))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/v1/invoices/{id}/issue", id)
                        .header("Authorization", bearer(t)))
                .andExpect(status().isOk());
    }

    private JsonNode getReport(TenantSetup t, String path, String... params) throws Exception {
        var req = get(path).header("Authorization", bearer(t));
        for (int i = 0; i < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        MvcResult result = mockMvc.perform(req).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void trialBalanceIsIsolated() throws Exception {
        // A postings: JE-1 (Cash 1000 / Revenue 1000), recorded expense
        // auto-journal (Expense 100 / Cash 100), issued-invoice auto-journal
        // (AR 550 / Revenue 550) -> debits = credits = 1650.
        JsonNode tbA = getReport(orgA, "/api/v1/reports/trial-balance",
                "fromDate", "2026-01-01", "toDate", "2026-12-31");
        JsonNode tbB = getReport(orgB, "/api/v1/reports/trial-balance",
                "fromDate", "2026-01-01", "toDate", "2026-12-31");
        assertThat(tbA.get("totalDebits").decimalValue()).isEqualByComparingTo("1650");
        assertThat(tbA.get("totalCredits").decimalValue()).isEqualByComparingTo("1650");
        // B postings: 5000 + 300 + 1650 per side.
        assertThat(tbB.get("totalDebits").decimalValue()).isEqualByComparingTo("6950");
        assertThat(tbB.get("totalCredits").decimalValue()).isEqualByComparingTo("6950");
    }

    @Test
    void generalLedgerRejectsCrossTenantAccount() throws Exception {
        // Guessed account ID from org B used with org A's token.
        mockMvc.perform(get("/api/v1/reports/general-ledger")
                        .header("Authorization", bearer(orgA))
                        .param("accountId", orgB.cashId)
                        .param("fromDate", "2026-01-01")
                        .param("toDate", "2026-12-31"))
                .andExpect(status().isNotFound());

        // And vice versa.
        mockMvc.perform(get("/api/v1/reports/general-ledger")
                        .header("Authorization", bearer(orgB))
                        .param("accountId", orgA.cashId)
                        .param("fromDate", "2026-01-01")
                        .param("toDate", "2026-12-31"))
                .andExpect(status().isNotFound());
    }

    @Test
    void generalLedgerShowsOnlyOwnEntries() throws Exception {
        JsonNode pageA = getReport(orgA, "/api/v1/reports/general-ledger",
                "accountId", orgA.cashId, "fromDate", "2026-01-01", "toDate", "2026-12-31");
        // A cash: JE-1 Dr 1000 + expense journal Cr 100.
        assertThat(pageA.get("totalElements").asLong()).isEqualTo(2);
        for (JsonNode line : pageA.get("content")) {
            assertThat(line.get("accountCode").asText()).isEqualTo("1000");
        }
    }

    @Test
    void profitAndLossIsIsolated() throws Exception {
        // A revenue: 1000 (JE) + 550 (invoice auto-journal) = 1550; expenses 100.
        JsonNode pnlA = getReport(orgA, "/api/v1/reports/profit-and-loss",
                "fromDate", "2026-01-01", "toDate", "2026-12-31");
        assertThat(pnlA.get("totalRevenue").decimalValue()).isEqualByComparingTo("1550");
        assertThat(pnlA.get("totalExpenses").decimalValue()).isEqualByComparingTo("100");
        // B revenue: 5000 + 1650 = 6650; expenses 300.
        JsonNode pnlB = getReport(orgB, "/api/v1/reports/profit-and-loss",
                "fromDate", "2026-01-01", "toDate", "2026-12-31");
        assertThat(pnlB.get("totalRevenue").decimalValue()).isEqualByComparingTo("6650");
        assertThat(pnlB.get("totalExpenses").decimalValue()).isEqualByComparingTo("300");
    }

    @Test
    void balanceSheetIsIsolated() throws Exception {
        // A assets: Cash 1000-100=900 + AR 550 (invoice) = 1450.
        JsonNode bsA = getReport(orgA, "/api/v1/reports/balance-sheet", "asOfDate", "2026-12-31");
        assertThat(bsA.get("totalAssets").decimalValue()).isEqualByComparingTo("1450");
        // B assets: Cash 5000-300=4700 + AR 1650 = 6350.
        JsonNode bsB = getReport(orgB, "/api/v1/reports/balance-sheet", "asOfDate", "2026-12-31");
        assertThat(bsB.get("totalAssets").decimalValue()).isEqualByComparingTo("6350");
    }

    @Test
    void expenseAndInvoiceReportsAreIsolated() throws Exception {
        JsonNode expA = getReport(orgA, "/api/v1/reports/expenses",
                "fromDate", "2026-01-01", "toDate", "2026-12-31");
        assertThat(expA.get("totalAmount").decimalValue()).isEqualByComparingTo("100");
        assertThat(expA.get("byVendor").get(0).get("vendorName").asText()).isEqualTo("Shared Vendor");

        JsonNode expB = getReport(orgB, "/api/v1/reports/expenses",
                "fromDate", "2026-01-01", "toDate", "2026-12-31");
        assertThat(expB.get("totalAmount").decimalValue()).isEqualByComparingTo("300");

        JsonNode invA = getReport(orgA, "/api/v1/reports/invoices",
                "fromDate", "2026-01-01", "toDate", "2026-12-31");
        assertThat(invA.get("invoiceCount").asLong()).isEqualTo(1);
        assertThat(invA.get("totalInvoiced").decimalValue()).isEqualByComparingTo("550");

        JsonNode invB = getReport(orgB, "/api/v1/reports/invoices",
                "fromDate", "2026-01-01", "toDate", "2026-12-31");
        assertThat(invB.get("totalInvoiced").decimalValue()).isEqualByComparingTo("1650");

        // Cross-tenant vendor/customer filters are rejected.
        mockMvc.perform(get("/api/v1/reports/expenses")
                        .header("Authorization", bearer(orgA))
                        .param("fromDate", "2026-01-01").param("toDate", "2026-12-31")
                        .param("vendorId", orgB.vendorId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/reports/invoices")
                        .header("Authorization", bearer(orgA))
                        .param("fromDate", "2026-01-01").param("toDate", "2026-12-31")
                        .param("customerId", orgB.customerId))
                .andExpect(status().isNotFound());
    }

    @Test
    void dashboardIsIsolated() throws Exception {
        JsonNode dashA = getReport(orgA, "/api/v1/reports/dashboard",
                "fromDate", "2026-01-01", "toDate", "2026-12-31");
        assertThat(dashA.get("totalRevenue").decimalValue()).isEqualByComparingTo("1550");
        assertThat(dashA.get("totalInvoiced").decimalValue()).isEqualByComparingTo("550");
        assertThat(dashA.get("invoiceCount").asLong()).isEqualTo(1);

        JsonNode dashB = getReport(orgB, "/api/v1/reports/dashboard",
                "fromDate", "2026-01-01", "toDate", "2026-12-31");
        assertThat(dashB.get("totalRevenue").decimalValue()).isEqualByComparingTo("6650");
        assertThat(dashB.get("totalInvoiced").decimalValue()).isEqualByComparingTo("1650");
    }
}
