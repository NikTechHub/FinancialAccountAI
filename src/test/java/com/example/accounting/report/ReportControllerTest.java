package com.example.accounting.report;

import com.example.accounting.dto.CreateMembershipRequest;
import com.example.accounting.dto.RegisterRequest;
import com.example.accounting.entity.MembershipRole;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Trial balance + general ledger + report validation/security tests.
 *
 * <p>Deterministic dataset (all POSTED unless noted):</p>
 * <ul>
 *   <li>E1 2026-01-05 owner investment: Dr Cash 5000 / Cr Equity 5000</li>
 *   <li>E2 2026-01-15 sale: Dr AR 1000 / Cr Revenue 1000</li>
 *   <li>E3 2026-02-10 supplies: Dr Expense 400 / Cr Cash 400</li>
 *   <li>E4 2026-03-01 loan: Dr Cash 2000 / Cr Liability 2000</li>
 *   <li>E5 DRAFT (never posted): Dr Expense 999 / Cr Cash 999</li>
 *   <li>E6 VOID: Dr Revenue 777 / Cr AR 777</li>
 *   <li>E7 future POSTED 2030-01-01: Dr Cash 111 / Cr Revenue 111</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String token;
    private String orgId;
    private String cashId;
    private String arId;
    private String revenueId;
    private String expenseId;
    private String liabilityId;
    private String equityId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = "rpt-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Report", "Owner",
                "report.owner." + suffix + "@example.com",
                "SecurePass123",
                "RptOrg-" + suffix,
                "Legal " + suffix,
                "PRIVATE_LIMITED",
                "India", "INR", "Asia/Kolkata"
        );
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode regJson = objectMapper.readTree(reg.getResponse().getContentAsString());
        token = regJson.get("accessToken").asText();
        orgId = regJson.get("organization").get("id").asText();

        cashId = createAccount("1000", "Cash", "ASSET");
        arId = createAccount("1100", "Accounts Receivable", "ASSET");
        revenueId = createAccount("4000", "Service Revenue", "REVENUE");
        expenseId = createAccount("5000", "Office Expense", "EXPENSE");
        liabilityId = createAccount("2000", "Bank Loan", "LIABILITY");
        equityId = createAccount("3000", "Owner Capital", "EQUITY");

        postJournal("JE-E1", "2026-01-05", cashId, equityId, "5000");
        postJournal("JE-E2", "2026-01-15", arId, revenueId, "1000");
        postJournal("JE-E3", "2026-02-10", expenseId, cashId, "400");
        postJournal("JE-E4", "2026-03-01", cashId, liabilityId, "2000");
        // Draft: created but never posted.
        createJournal("JE-E5", "2026-02-15", expenseId, cashId, "999");
        // Void: draft voided via DELETE.
        String voidId = createJournal("JE-E6", "2026-02-20", revenueId, arId, "777");
        mockMvc.perform(delete("/api/v1/journal-entries/{id}", voidId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());
        // Future-dated posted entry.
        postJournal("JE-E7", "2030-01-01", cashId, revenueId, "111");
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private String createAccount(String code, String name, String type) throws Exception {
        String json = """
                {"code":"%s","name":"%s","type":"%s"}
                """.formatted(code, name, type);
        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createJournal(String entryNumber, String date, String debitAccountId,
                                 String creditAccountId, String amount) throws Exception {
        String json = """
                {"entryNumber":"%s","entryDate":"%s","description":"test entry",
                 "lines":[{"accountId":"%s","debitAmount":%s,"creditAmount":0},
                          {"accountId":"%s","debitAmount":0,"creditAmount":%s}]}
                """.formatted(entryNumber, date, debitAccountId, amount, creditAccountId, amount);
        MvcResult result = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void postJournal(String entryNumber, String date, String debitAccountId,
                             String creditAccountId, String amount) throws Exception {
        String id = createJournal(entryNumber, date, debitAccountId, creditAccountId, amount);
        mockMvc.perform(post("/api/v1/journal-entries/{id}/post", id)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());
    }

    private JsonNode getTrialBalance(String from, String to) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/reports/trial-balance")
                        .header("Authorization", bearer())
                        .param("fromDate", from)
                        .param("toDate", to))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static BigDecimal dec(JsonNode node, String field) {
        return node.get(field).decimalValue();
    }

    private static void assertDecimal(JsonNode node, String field, String expected) {
        assertThat(node.get(field).decimalValue()).isEqualByComparingTo(expected);
    }

    @Test
    void trialBalanceReportsBalancedDataset() throws Exception {
        JsonNode tb = getTrialBalance("2026-01-01", "2026-12-31");

        assertThat(tb.get("lines").size()).isEqualTo(6);
        assertDecimal(tb, "totalDebits", "8400");
        assertDecimal(tb, "totalCredits", "8400");
        assertDecimal(tb, "totalDifference", "0");

        JsonNode cash = findLine(tb, "1000");
        assertDecimal(cash, "debitTotal", "7000");
        assertDecimal(cash, "creditTotal", "400");
        assertDecimal(cash, "netBalance", "6600");

        JsonNode ar = findLine(tb, "1100");
        assertDecimal(ar, "debitTotal", "1000");
        assertDecimal(ar, "creditTotal", "0");
        assertDecimal(ar, "netBalance", "1000");

        JsonNode revenue = findLine(tb, "4000");
        assertDecimal(revenue, "debitTotal", "0");
        assertDecimal(revenue, "creditTotal", "1000");
        // Credit-normal balance.
        assertDecimal(revenue, "netBalance", "1000");
    }

    @Test
    void trialBalanceRespectsDateFiltering() throws Exception {
        // February contains only E3 (supplies).
        JsonNode tb = getTrialBalance("2026-02-01", "2026-02-28");
        assertThat(tb.get("lines").size()).isEqualTo(2);
        assertDecimal(tb, "totalDebits", "400");
        assertDecimal(tb, "totalCredits", "400");
        assertDecimal(tb, "totalDifference", "0");
    }

    @Test
    void trialBalanceExcludesDraftVoidAndFutureEntries() throws Exception {
        // Draft 999, void 777 and future 111 would break these exact totals if included.
        JsonNode tb = getTrialBalance("2026-01-01", "2026-12-31");
        assertDecimal(tb, "totalDebits", "8400");
        assertDecimal(tb, "totalCredits", "8400");

        // Future entry visible only when the range covers it.
        JsonNode withFuture = getTrialBalance("2026-01-01", "2030-12-31");
        assertDecimal(withFuture, "totalDebits", "8511");
        assertDecimal(withFuture, "totalCredits", "8511");
    }

    @Test
    void trialBalanceEmptyRangeReturnsZeros() throws Exception {
        JsonNode tb = getTrialBalance("2025-01-01", "2025-12-31");
        assertThat(tb.get("lines").size()).isEqualTo(0);
        assertDecimal(tb, "totalDebits", "0");
        assertDecimal(tb, "totalCredits", "0");
        assertDecimal(tb, "totalDifference", "0");
    }

    @Test
    void trialBalanceRejectsInvalidDateRange() throws Exception {
        mockMvc.perform(get("/api/v1/reports/trial-balance")
                        .header("Authorization", bearer())
                        .param("fromDate", "2026-12-31")
                        .param("toDate", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void trialBalanceRejectsMissingDates() throws Exception {
        mockMvc.perform(get("/api/v1/reports/trial-balance")
                        .header("Authorization", bearer())
                        .param("fromDate", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generalLedgerReturnsDeterministicOrdering() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/reports/general-ledger")
                        .header("Authorization", bearer())
                        .param("accountId", cashId)
                        .param("fromDate", "2026-01-01")
                        .param("toDate", "2026-12-31"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode content = page.get("content");
        // Cash lines: E1 Dr 5000 (Jan), E3 Cr 400 (Feb), E4 Dr 2000 (Mar).
        assertThat(content.size()).isEqualTo(3);
        assertThat(content.get(0).get("entryNumber").asText()).isEqualTo("JE-E1");
        assertThat(content.get(1).get("entryNumber").asText()).isEqualTo("JE-E3");
        assertThat(content.get(2).get("entryNumber").asText()).isEqualTo("JE-E4");
        assertThat(content.get(0).get("debitAmount").decimalValue()).isEqualByComparingTo("5000");
        assertThat(content.get(1).get("creditAmount").decimalValue()).isEqualByComparingTo("400");
        assertThat(content.get(0).get("accountCode").asText()).isEqualTo("1000");
        assertThat(content.get(0).get("entryDate").asText()).isEqualTo("2026-01-05");
    }

    @Test
    void generalLedgerRespectsDateFiltering() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/reports/general-ledger")
                        .header("Authorization", bearer())
                        .param("accountId", cashId)
                        .param("fromDate", "2026-02-01")
                        .param("toDate", "2026-02-28"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.get(0).get("entryNumber").asText()).isEqualTo("JE-E3");
    }

    @Test
    void generalLedgerRejectsUnknownAccount() throws Exception {
        mockMvc.perform(get("/api/v1/reports/general-ledger")
                        .header("Authorization", bearer())
                        .param("accountId", UUID.randomUUID().toString())
                        .param("fromDate", "2026-01-01")
                        .param("toDate", "2026-12-31"))
                .andExpect(status().isNotFound());
    }

    @Test
    void generalLedgerRejectsInvalidAccountId() throws Exception {
        mockMvc.perform(get("/api/v1/reports/general-ledger")
                        .header("Authorization", bearer())
                        .param("accountId", "not-a-uuid")
                        .param("fromDate", "2026-01-01")
                        .param("toDate", "2026-12-31"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedRequestsAreDeniedOnAllReportEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/reports/trial-balance")
                        .param("fromDate", "2026-01-01").param("toDate", "2026-12-31"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reports/general-ledger")
                        .param("accountId", cashId)
                        .param("fromDate", "2026-01-01").param("toDate", "2026-12-31"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reports/profit-and-loss")
                        .param("fromDate", "2026-01-01").param("toDate", "2026-12-31"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reports/balance-sheet").param("asOfDate", "2026-12-31"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reports/expenses")
                        .param("fromDate", "2026-01-01").param("toDate", "2026-12-31"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reports/invoices")
                        .param("fromDate", "2026-01-01").param("toDate", "2026-12-31"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reports/dashboard")
                        .param("fromDate", "2026-01-01").param("toDate", "2026-12-31"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allAuthenticatedRolesCanReadReports() throws Exception {
        for (MembershipRole role : MembershipRole.values()) {
            if (role == MembershipRole.OWNER) {
                continue; // owner token already covers OWNER below
            }
            String roleToken = addMemberWithRole(role);
            mockMvc.perform(get("/api/v1/reports/trial-balance")
                            .header("Authorization", "Bearer " + roleToken)
                            .param("fromDate", "2026-01-01")
                            .param("toDate", "2026-12-31"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/reports/dashboard")
                            .header("Authorization", "Bearer " + roleToken)
                            .param("fromDate", "2026-01-01")
                            .param("toDate", "2026-12-31"))
                    .andExpect(status().isOk());
        }
        // OWNER itself.
        mockMvc.perform(get("/api/v1/reports/trial-balance")
                        .header("Authorization", bearer())
                        .param("fromDate", "2026-01-01")
                        .param("toDate", "2026-12-31"))
                .andExpect(status().isOk());
    }

    private String addMemberWithRole(MembershipRole role) throws Exception {
        String suffix = "member-" + role.name() + "-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Member", role.name(),
                "member." + suffix + "@example.com",
                "SecurePass123",
                "MemberOrg-" + suffix,
                "Legal " + suffix,
                "LLP", "India", "INR", "Asia/Kolkata"
        );
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode regJson = objectMapper.readTree(reg.getResponse().getContentAsString());
        String memberToken = regJson.get("accessToken").asText();
        String memberUserId = regJson.get("user").get("id").asText();

        String membershipJson = """
                {"userId":"%s","role":"%s"}
                """.formatted(memberUserId, role.name());
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", orgId)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(membershipJson))
                .andExpect(status().isCreated());
        return memberToken;
    }

    private static JsonNode findLine(JsonNode trialBalance, String code) {
        for (JsonNode line : trialBalance.get("lines")) {
            if (line.get("accountCode").asText().equals(code)) {
                return line;
            }
        }
        throw new AssertionError("No trial balance line for code " + code);
    }
}
