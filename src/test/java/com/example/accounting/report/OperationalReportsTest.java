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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Expense summary, invoice summary and dashboard tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperationalReportsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String token;
    private String cashId;
    private String expenseId;
    private String revenueId;
    private String arId;
    private String vendorId;
    private String customerId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = "ops-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Ops", "Owner",
                "ops.owner." + suffix + "@example.com",
                "SecurePass123",
                "OpsOrg-" + suffix,
                "Legal " + suffix,
                "PRIVATE_LIMITED",
                "India", "INR", "Asia/Kolkata"
        );
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        token = objectMapper.readTree(reg.getResponse().getContentAsString()).get("accessToken").asText();

        cashId = createAccount("1000", "Cash", "ASSET");
        arId = createAccount("1100", "Accounts Receivable", "ASSET");
        revenueId = createAccount("4000", "Service Revenue", "REVENUE");
        expenseId = createAccount("5000", "Office Expense", "EXPENSE");
        vendorId = createVendor("Acme Supplies");
        customerId = createCustomer("Globex Corp");
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private String createAccount(String code, String name, String type) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"%s","type":"%s"}
                                """.formatted(code, name, type)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createVendor(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createCustomer(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createExpense(String vendor, String amount, String date) throws Exception {
        String json = """
                {"vendorId":%s,"expenseAccountId":"%s","paymentAccountId":"%s",
                 "amount":%s,"expenseDate":"%s","description":"office supplies"}
                """.formatted(vendor == null ? "null" : "\"" + vendor + "\"", expenseId, cashId, amount, date);
        MvcResult result = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void recordExpense(String id) throws Exception {
        mockMvc.perform(post("/api/v1/expenses/{id}/record", id)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());
    }

    private String createInvoice(String customer, String number, String date) throws Exception {
        String json = """
                {"customerId":"%s","invoiceNumber":"%s","invoiceDate":"%s",
                 "lines":[{"description":"Consulting","quantity":2,"unitPrice":500,
                           "taxRate":10,"discountAmount":0,"revenueAccountId":"%s"}]}
                """.formatted(customer, number, date, revenueId);
        MvcResult result = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void issueInvoice(String id) throws Exception {
        mockMvc.perform(post("/api/v1/invoices/{id}/issue", id)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());
    }

    private void postJournal(String entryNumber, String date, String debitAccountId,
                             String creditAccountId, String amount) throws Exception {
        String json = """
                {"entryNumber":"%s","entryDate":"%s","description":"test entry",
                 "lines":[{"accountId":"%s","debitAmount":%s,"creditAmount":0},
                          {"accountId":"%s","debitAmount":0,"creditAmount":%s}]}
                """.formatted(entryNumber, date, debitAccountId, amount, creditAccountId, amount);
        MvcResult created = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/v1/journal-entries/{id}/post", id)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());
    }

    private JsonNode getExpenses(String from, String to, String vendor) throws Exception {
        var req = get("/api/v1/reports/expenses")
                .header("Authorization", bearer())
                .param("fromDate", from)
                .param("toDate", to);
        if (vendor != null) {
            req = req.param("vendorId", vendor);
        }
        MvcResult result = mockMvc.perform(req).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getInvoices(String from, String to, String customer) throws Exception {
        var req = get("/api/v1/reports/invoices")
                .header("Authorization", bearer())
                .param("fromDate", from)
                .param("toDate", to);
        if (customer != null) {
            req = req.param("customerId", customer);
        }
        MvcResult result = mockMvc.perform(req).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void expenseSummaryGroupsByAccountAndVendor() throws Exception {
        recordExpense(createExpense(vendorId, "250", "2026-04-01"));

        JsonNode summary = getExpenses("2026-01-01", "2026-12-31", null);
        assertThat(summary.get("totalAmount").decimalValue()).isEqualByComparingTo("250");
        assertThat(summary.get("expenseCount").asLong()).isEqualTo(1);
        assertThat(summary.get("byAccount").size()).isEqualTo(1);
        assertThat(summary.get("byAccount").get(0).get("expenseAccountCode").asText()).isEqualTo("5000");
        assertThat(summary.get("byAccount").get(0).get("totalAmount").decimalValue())
                .isEqualByComparingTo("250");
        assertThat(summary.get("byVendor").size()).isEqualTo(1);
        assertThat(summary.get("byVendor").get(0).get("vendorName").asText()).isEqualTo("Acme Supplies");
        assertThat(summary.get("byVendor").get(0).get("totalAmount").decimalValue())
                .isEqualByComparingTo("250");
    }

    @Test
    void expenseSummaryExcludesDraftAndVoid() throws Exception {
        recordExpense(createExpense(vendorId, "250", "2026-04-01"));
        // Draft left unrecorded.
        createExpense(vendorId, "999", "2026-04-02");
        // Voided draft.
        String voidId = createExpense(vendorId, "888", "2026-04-03");
        mockMvc.perform(delete("/api/v1/expenses/{id}", voidId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());

        JsonNode summary = getExpenses("2026-01-01", "2026-12-31", null);
        assertThat(summary.get("totalAmount").decimalValue()).isEqualByComparingTo("250");
        assertThat(summary.get("expenseCount").asLong()).isEqualTo(1);
    }

    @Test
    void expenseSummarySupportsDateAndVendorFilters() throws Exception {
        String vendor2 = createVendor("Second Vendor " + System.nanoTime());
        recordExpense(createExpense(vendorId, "250", "2026-04-01"));
        recordExpense(createExpense(vendor2, "100", "2026-05-01"));

        JsonNode april = getExpenses("2026-04-01", "2026-04-30", null);
        assertThat(april.get("totalAmount").decimalValue()).isEqualByComparingTo("250");

        JsonNode filtered = getExpenses("2026-01-01", "2026-12-31", vendor2);
        assertThat(filtered.get("totalAmount").decimalValue()).isEqualByComparingTo("100");
        assertThat(filtered.get("byVendor").size()).isEqualTo(1);
        assertThat(filtered.get("byAccount").size()).isEqualTo(1);
    }

    @Test
    void invoiceSummaryReportsCountInvoicedPaidAndOutstanding() throws Exception {
        // 2 x 500 = 1000 subtotal, 10% tax = 100, total 1100.
        issueInvoice(createInvoice(customerId, "INV-001", "2026-05-01"));

        JsonNode summary = getInvoices("2026-01-01", "2026-12-31", null);
        assertThat(summary.get("invoiceCount").asLong()).isEqualTo(1);
        assertThat(summary.get("totalInvoiced").decimalValue()).isEqualByComparingTo("1100");
        // No payment processing exists yet; stored values reported as-is.
        assertThat(summary.get("totalPaid").decimalValue()).isEqualByComparingTo("0");
        assertThat(summary.get("totalOutstanding").decimalValue()).isEqualByComparingTo("1100");
    }

    @Test
    void invoiceSummaryExcludesDraftAndSupportsFilters() throws Exception {
        issueInvoice(createInvoice(customerId, "INV-001", "2026-05-01"));
        // Draft left unissued.
        createInvoice(customerId, "INV-DRAFT", "2026-05-02");

        JsonNode summary = getInvoices("2026-01-01", "2026-12-31", null);
        assertThat(summary.get("invoiceCount").asLong()).isEqualTo(1);

        JsonNode april = getInvoices("2026-04-01", "2026-04-30", null);
        assertThat(april.get("invoiceCount").asLong()).isEqualTo(0);
        assertThat(april.get("totalInvoiced").decimalValue()).isEqualByComparingTo("0");

        JsonNode byCustomer = getInvoices("2026-01-01", "2026-12-31", customerId);
        assertThat(byCustomer.get("invoiceCount").asLong()).isEqualTo(1);
    }

    @Test
    void dashboardReturnsCompactSummary() throws Exception {
        // Manual revenue journal: Dr AR 1000 / Cr Revenue 1000.
        postJournal("JE-DASH", "2026-06-01", arId, revenueId, "1000");
        recordExpense(createExpense(vendorId, "250", "2026-06-02"));
        // Issued invoice adds Dr AR 1100 / Cr Revenue 1100 via auto-journal.
        issueInvoice(createInvoice(customerId, "INV-001", "2026-06-03"));

        MvcResult result = mockMvc.perform(get("/api/v1/reports/dashboard")
                        .header("Authorization", bearer())
                        .param("fromDate", "2026-01-01")
                        .param("toDate", "2026-12-31"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode dash = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(dash.get("totalRevenue").decimalValue()).isEqualByComparingTo("2100");
        assertThat(dash.get("totalExpenses").decimalValue()).isEqualByComparingTo("250");
        assertThat(dash.get("netProfit").decimalValue()).isEqualByComparingTo("1850");
        assertThat(dash.get("totalInvoiced").decimalValue()).isEqualByComparingTo("1100");
        assertThat(dash.get("totalOutstanding").decimalValue()).isEqualByComparingTo("1100");
        assertThat(dash.get("customerCount").asLong()).isEqualTo(1);
        assertThat(dash.get("vendorCount").asLong()).isEqualTo(1);
        assertThat(dash.get("invoiceCount").asLong()).isEqualTo(1);
        assertThat(dash.get("expenseCount").asLong()).isEqualTo(1);
    }

    @Test
    void operationalReportsRejectInvalidDateRange() throws Exception {
        mockMvc.perform(get("/api/v1/reports/expenses")
                        .header("Authorization", bearer())
                        .param("fromDate", "2026-12-31")
                        .param("toDate", "2026-01-01"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/reports/invoices")
                        .header("Authorization", bearer())
                        .param("fromDate", "2026-12-31")
                        .param("toDate", "2026-01-01"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/reports/dashboard")
                        .header("Authorization", bearer())
                        .param("fromDate", "2026-12-31")
                        .param("toDate", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }
}
