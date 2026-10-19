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
 * Profit &amp; loss and balance sheet tests over the deterministic dataset:
 * E1 investment 5000, E2 sale 1000, E3 supplies 400, E4 loan 2000,
 * plus a DRAFT 999, a VOID 777 and a future POSTED 111 entry.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FinancialStatementsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String token;
    private String cashId;
    private String arId;
    private String revenueId;
    private String expenseId;
    private String liabilityId;
    private String equityId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = "fin-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Finance", "Owner",
                "finance.owner." + suffix + "@example.com",
                "SecurePass123",
                "FinOrg-" + suffix,
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
        liabilityId = createAccount("2000", "Bank Loan", "LIABILITY");
        equityId = createAccount("3000", "Owner Capital", "EQUITY");

        postJournal("JE-E1", "2026-01-05", cashId, equityId, "5000");
        postJournal("JE-E2", "2026-01-15", arId, revenueId, "1000");
        postJournal("JE-E3", "2026-02-10", expenseId, cashId, "400");
        postJournal("JE-E4", "2026-03-01", cashId, liabilityId, "2000");
        createJournal("JE-E5", "2026-02-15", expenseId, cashId, "999");
        String voidId = createJournal("JE-E6", "2026-02-20", revenueId, arId, "777");
        mockMvc.perform(delete("/api/v1/journal-entries/{id}", voidId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());
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

    private JsonNode getProfitAndLoss(String from, String to) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/reports/profit-and-loss")
                        .header("Authorization", bearer())
                        .param("fromDate", from)
                        .param("toDate", to))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getBalanceSheet(String asOf) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/reports/balance-sheet")
                        .header("Authorization", bearer())
                        .param("asOfDate", asOf))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void profitAndLossCalculatesRevenueExpensesAndNetProfit() throws Exception {
        JsonNode pnl = getProfitAndLoss("2026-01-01", "2026-12-31");

        assertThat(pnl.get("revenueLines").size()).isEqualTo(1);
        assertThat(pnl.get("revenueLines").get(0).get("accountCode").asText()).isEqualTo("4000");
        assertThat(pnl.get("revenueLines").get(0).get("amount").decimalValue())
                .isEqualByComparingTo("1000");
        assertThat(pnl.get("expenseLines").size()).isEqualTo(1);
        assertThat(pnl.get("expenseLines").get(0).get("accountCode").asText()).isEqualTo("5000");
        assertThat(pnl.get("expenseLines").get(0).get("amount").decimalValue())
                .isEqualByComparingTo("400");
        assertThat(pnl.get("totalRevenue").decimalValue()).isEqualByComparingTo("1000");
        assertThat(pnl.get("totalExpenses").decimalValue()).isEqualByComparingTo("400");
        assertThat(pnl.get("netProfit").decimalValue()).isEqualByComparingTo("600");
    }

    @Test
    void profitAndLossRespectsDateFiltering() throws Exception {
        // January: only the sale. No expenses yet.
        JsonNode pnl = getProfitAndLoss("2026-01-01", "2026-01-31");
        assertThat(pnl.get("totalRevenue").decimalValue()).isEqualByComparingTo("1000");
        assertThat(pnl.get("totalExpenses").decimalValue()).isEqualByComparingTo("0");
        assertThat(pnl.get("netProfit").decimalValue()).isEqualByComparingTo("1000");
        assertThat(pnl.get("expenseLines").size()).isEqualTo(0);
    }

    @Test
    void profitAndLossExcludesDraftVoidAndFutureEntries() throws Exception {
        JsonNode pnl = getProfitAndLoss("2026-01-01", "2026-12-31");
        assertThat(pnl.get("totalRevenue").decimalValue()).isEqualByComparingTo("1000");
        assertThat(pnl.get("totalExpenses").decimalValue()).isEqualByComparingTo("400");

        // Invalid range rejected.
        mockMvc.perform(get("/api/v1/reports/profit-and-loss")
                        .header("Authorization", bearer())
                        .param("fromDate", "2026-06-01")
                        .param("toDate", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void balanceSheetReportsAssetsLiabilitiesAndEquity() throws Exception {
        JsonNode bs = getBalanceSheet("2026-12-31");

        assertThat(bs.get("assetLines").size()).isEqualTo(2);
        assertThat(bs.get("totalAssets").decimalValue()).isEqualByComparingTo("7600");
        assertThat(bs.get("liabilityLines").size()).isEqualTo(1);
        assertThat(bs.get("totalLiabilities").decimalValue()).isEqualByComparingTo("2000");
        assertThat(bs.get("equityLines").size()).isEqualTo(1);
        assertThat(bs.get("totalEquity").decimalValue()).isEqualByComparingTo("5000");

        // Assets use debit-normal balances: Cash 7000-400=6600.
        JsonNode cash = findLine(bs, "assetLines", "1000");
        assertThat(cash.get("balance").decimalValue()).isEqualByComparingTo("6600");
    }

    @Test
    void balanceSheetRespectsAsOfDate() throws Exception {
        // End of January: investment + sale only.
        JsonNode bs = getBalanceSheet("2026-01-31");
        assertThat(bs.get("totalAssets").decimalValue()).isEqualByComparingTo("6000");
        assertThat(bs.get("totalLiabilities").decimalValue()).isEqualByComparingTo("0");
        assertThat(bs.get("totalEquity").decimalValue()).isEqualByComparingTo("5000");
        assertThat(bs.get("liabilityLines").size()).isEqualTo(0);
    }

    @Test
    void balanceSheetExcludesFutureEntries() throws Exception {
        JsonNode bs = getBalanceSheet("2026-12-31");
        // The 2030 Cash/Revenue 111 entry must not leak in.
        assertThat(bs.get("totalAssets").decimalValue()).isEqualByComparingTo("7600");
    }

    @Test
    void balanceSheetRequiresAsOfDate() throws Exception {
        mockMvc.perform(get("/api/v1/reports/balance-sheet")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    private static JsonNode findLine(JsonNode bs, String section, String code) {
        for (JsonNode line : bs.get(section)) {
            if (line.get("accountCode").asText().equals(code)) {
                return line;
            }
        }
        throw new AssertionError("No balance sheet line for code " + code);
    }
}
