package com.example.accounting.controller;

import com.example.accounting.dto.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExpenseControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private String ownerToken;
    private String ownerUserId;
    private String orgId;
    private String expenseAccountId;
    private String paymentAccountId;
    private String vendorId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = "exp-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Expense", "Owner",
                "expense.owner." + suffix + "@example.com",
                "SecurePass123",
                "ExpenseOrg-" + suffix,
                "Legal Expense " + suffix,
                "PRIVATE_LIMITED",
                "India", "INR", "Asia/Kolkata"
        );
        String resp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        ownerToken = objectMapper.readTree(resp).get("accessToken").asText();
        ownerUserId = objectMapper.readTree(resp).get("user").get("id").asText();
        orgId = objectMapper.readTree(resp).get("organization").get("id").asText();

        expenseAccountId = createAccount("5000-" + suffix.substring(0, 8), "Office Expenses", "EXPENSE");
        paymentAccountId = createAccount("1000-" + suffix.substring(0, 8), "Cash", "ASSET");
        vendorId = createVendor("Vendor-" + suffix);
    }

    private String authHeader() {
        return "Bearer " + ownerToken;
    }

    private String authHeader(String token) {
        return "Bearer " + token;
    }

    private String createAccount(String code, String name, String type) throws Exception {
        String json = """
                {"code":"%s","name":"%s","type":"%s"}
                """.formatted(code, name, type);
        String resp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("id").asText();
    }

    private String createVendor(String name) throws Exception {
        String json = """
                {"name":"%s","email":"%s@example.com"}
                """.formatted(name, name.replace(" ", "").toLowerCase() + System.nanoTime() % 10000);
        String resp = mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("id").asText();
    }

    private String createExpenseJson(String vendorId, String expAcc, String payAcc, String amount, String desc, String ref) {
        String vendorPart = vendorId != null ? "\"vendorId\":\"" + vendorId + "\"," : "";
        return """
                {
                    %s
                    "expenseAccountId":"%s",
                    "paymentAccountId":"%s",
                    "amount":%s,
                    "expenseDate":"%s",
                    "description":"%s",
                    "reference":"%s"
                }
                """.formatted(vendorPart, expAcc, payAcc, amount, LocalDate.now().toString(), desc, ref);
    }

    @Test
    void shouldCreateDraftExpense() throws Exception {
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "100.00", "Office supplies", "REF-001");
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.journalEntryId").doesNotExist());
    }

    @Test
    void shouldRetrieveExpense() throws Exception {
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "200.00", "Retrieve test", "REF-GET");
        String resp = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        mockMvc.perform(get("/api/v1/expenses/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.amount").value(200.00));
    }

    @Test
    void shouldUpdateDraftExpense() throws Exception {
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "150.00", "Before", "REF-BEFORE");
        String resp = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        String updateJson = """
                {"description":"Updated","amount":175.50}
                """;
        mockMvc.perform(put("/api/v1/expenses/{id}", id)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Updated"))
                .andExpect(jsonPath("$.amount").value(175.50));
    }

    @Test
    void shouldRecordExpenseAndCreateJournalEntry() throws Exception {
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "1000.00", "Software subscription", "REF-REC");
        String resp = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();

        String recordResp = mockMvc.perform(post("/api/v1/expenses/{id}/record", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECORDED"))
                .andExpect(jsonPath("$.journalEntryId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String journalId = objectMapper.readTree(recordResp).get("journalEntryId").asText();

        // verify journal is POSTED and balanced
        mockMvc.perform(get("/api/v1/journal-entries/{id}", journalId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.totalDebits").value(1000.00))
                .andExpect(jsonPath("$.totalCredits").value(1000.00))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].debitAmount").exists())
                .andExpect(jsonPath("$.lines[1].creditAmount").exists());

        // expense should be RECORDED and have journalEntryId
        mockMvc.perform(get("/api/v1/expenses/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECORDED"))
                .andExpect(jsonPath("$.journalEntryId").value(journalId));
    }

    @Test
    void shouldRejectZeroAmount() throws Exception {
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "0.00", "Zero", "REF-ZERO");
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
        String jsonNeg = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "-100.00", "Neg", "REF-NEG");
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonNeg))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectNegativeAmount() throws Exception {
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "-50.00", "Neg", "REF-NEG2");
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectMissingExpenseAccount() throws Exception {
        String json = """
                {
                    "paymentAccountId":"%s",
                    "amount":100.00,
                    "expenseDate":"%s",
                    "description":"Missing exp acc"
                }
                """.formatted(paymentAccountId, LocalDate.now().toString());
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectMissingPaymentAccount() throws Exception {
        String json = """
                {
                    "expenseAccountId":"%s",
                    "amount":100.00,
                    "expenseDate":"%s"
                }
                """.formatted(expenseAccountId, LocalDate.now().toString());
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectInactiveExpenseAccount() throws Exception {
        String accCode = "INACT" + System.nanoTime() % 10000;
        String accResp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Inactive Exp","type":"EXPENSE"}
                                """.formatted(accCode)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String accId = objectMapper.readTree(accResp).get("id").asText();
        mockMvc.perform(put("/api/v1/accounts/{id}", accId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());

        String json = createExpenseJson(vendorId, accId, paymentAccountId, "100.00", "Inactive exp acc", "REF-INACT");
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("inactive")));
    }

    @Test
    void shouldRejectInactivePaymentAccount() throws Exception {
        String accCode = "INACTP" + System.nanoTime() % 10000;
        String accResp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Inactive Pay","type":"ASSET"}
                                """.formatted(accCode)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String accId = objectMapper.readTree(accResp).get("id").asText();
        mockMvc.perform(put("/api/v1/accounts/{id}", accId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());

        String json = createExpenseJson(vendorId, expenseAccountId, accId, "100.00", "Inactive pay", "REF-INACTP");
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectCrossTenantExpenseAccount() throws Exception {
        String suffix = "cross-acc-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Other", "Cross",
                "cross.exp." + suffix + "@example.com",
                "SecurePass123",
                "CrossOrgExp-" + suffix,
                "Legal Cross " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        String otherAccId = createAccountWithToken(otherToken, "CROSS" + System.nanoTime() % 10000, "Cross Acc", "EXPENSE");

        String json = createExpenseJson(vendorId, otherAccId, paymentAccountId, "100.00", "Cross exp", "REF-CROSS");
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("different organization")));
    }

    @Test
    void shouldRejectCrossTenantPaymentAccount() throws Exception {
        String suffix = "cross-pay-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Other", "CrossPay",
                "cross.pay." + suffix + "@example.com",
                "SecurePass123",
                "CrossOrgPay-" + suffix,
                "Legal Cross Pay " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        String otherPayId = createAccountWithToken(otherToken, "CROSSP" + System.nanoTime() % 10000, "Cross Pay", "ASSET");

        String json = createExpenseJson(vendorId, expenseAccountId, otherPayId, "100.00", "Cross pay", "REF-CROSSP");
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    private String createAccountWithToken(String token, String code, String name, String type) throws Exception {
        String json = """
                {"code":"%s","name":"%s","type":"%s"}
                """.formatted(code, name, type);
        String resp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("id").asText();
    }

    @Test
    void shouldAcceptValidVendor() throws Exception {
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "250.00", "With vendor", "REF-VENDOR");
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vendorId").value(vendorId));
    }

    @Test
    void shouldRejectCrossTenantVendor() throws Exception {
        String suffix = "cross-ven-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Other", "Ven",
                "cross.ven." + suffix + "@example.com",
                "SecurePass123",
                "CrossOrgVen-" + suffix,
                "Legal Cross Ven " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        String otherVendorId = createVendorWithToken(otherToken, "OtherVendor-" + suffix);

        String json = createExpenseJson(otherVendorId, expenseAccountId, paymentAccountId, "300.00", "Cross vendor", "REF-CROSSV");
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("different organization")));
    }

    private String createVendorWithToken(String token, String name) throws Exception {
        String json = """
                {"name":"%s","email":"%s@example.com"}
                """.formatted(name, name.toLowerCase().replace(" ", "") + System.nanoTime() % 10000);
        String resp = mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("id").asText();
    }

    @Test
    void shouldRejectInactiveVendor() throws Exception {
        String venName = "InactiveVendor-" + System.nanoTime();
        String venId = createVendor(venName);
        // deactivate vendor
        mockMvc.perform(delete("/api/v1/vendors/{id}", venId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isNoContent());
        String json = createExpenseJson(venId, expenseAccountId, paymentAccountId, "100.00", "Inactive vendor", "REF-INACTV");
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("inactive")));
    }

    @Test
    void shouldNotRecordAlreadyRecordedExpense() throws Exception {
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "500.00", "Record twice", "REF-REC2");
        String resp = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        mockMvc.perform(post("/api/v1/expenses/{id}/record", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/expenses/{id}/record", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldNotModifyRecordedExpense() throws Exception {
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "400.00", "Rec Mod", "REF-RECMOD");
        String resp = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        mockMvc.perform(post("/api/v1/expenses/{id}/record", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk());
        String updateJson = """
                {"description":"Hacked"}
                """;
        mockMvc.perform(put("/api/v1/expenses/{id}", id)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldVoidDraftExpense() throws Exception {
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "250.00", "Void draft", "REF-VOID");
        String resp = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        mockMvc.perform(delete("/api/v1/expenses/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"));
    }

    @Test
    void shouldNotVoidRecordedExpense() throws Exception {
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "350.00", "Void recorded", "REF-VOIDREC");
        String resp = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        mockMvc.perform(post("/api/v1/expenses/{id}/record", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/expenses/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldNotModifyVoidExpense() throws Exception {
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "300.00", "Void mod", "REF-VOIDMOD");
        String resp = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        mockMvc.perform(delete("/api/v1/expenses/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk());
        String updateJson = """
                {"description":"Hacked void"}
                """;
        mockMvc.perform(put("/api/v1/expenses/{id}", id)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldAllowEmployeeRead() throws Exception {
        String suffix = "emp-exp-" + System.nanoTime();
        String empEmail = "emp.exp." + suffix + "@example.com";
        String empJson = """
                {"firstName":"Emp","lastName":"User","email":"%s","password":"SecurePass123"}
                """.formatted(empEmail);
        String empResp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(empJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String empUserId = objectMapper.readTree(empResp).get("id").asText();
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", orgId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"EMPLOYEE"}
                                """.formatted(empUserId)))
                .andExpect(status().isCreated());
        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"SecurePass123"}
                                """.formatted(empEmail)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String empToken = objectMapper.readTree(loginResp).get("accessToken").asText();

        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "100.00", "Emp read", "REF-EMPREAD");
        String createResp = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String expId = objectMapper.readTree(createResp).get("id").asText();

        mockMvc.perform(get("/api/v1/expenses/{id}", expId)
                        .header("Authorization", "Bearer " + empToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/expenses")
                        .header("Authorization", "Bearer " + empToken))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectEmployeeWrite() throws Exception {
        String suffix = "emp2-exp-" + System.nanoTime();
        String empEmail = "emp2.exp." + suffix + "@example.com";
        String empJson = """
                {"firstName":"Emp2","lastName":"User","email":"%s","password":"SecurePass123"}
                """.formatted(empEmail);
        String empResp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(empJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String empUserId = objectMapper.readTree(empResp).get("id").asText();
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", orgId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"EMPLOYEE"}
                                """.formatted(empUserId)))
                .andExpect(status().isCreated());
        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"SecurePass123"}
                                """.formatted(empEmail)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String empToken = objectMapper.readTree(loginResp).get("accessToken").asText();

        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "200.00", "Emp write", "REF-EMPWRITE");
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + empToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowViewerRead() throws Exception {
        String suffix = "view-exp-" + System.nanoTime();
        String viewerEmail = "viewer.exp." + suffix + "@example.com";
        String viewerJson = """
                {"firstName":"Viewer","lastName":"User","email":"%s","password":"SecurePass123"}
                """.formatted(viewerEmail);
        String viewerResp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(viewerJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String viewerUserId = objectMapper.readTree(viewerResp).get("id").asText();
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", orgId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"VIEWER"}
                                """.formatted(viewerUserId)))
                .andExpect(status().isCreated());
        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"SecurePass123"}
                                """.formatted(viewerEmail)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewerToken = objectMapper.readTree(loginResp).get("accessToken").asText();

        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "150.00", "Viewer read", "REF-VIEWREAD");
        String createResp = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String expId = objectMapper.readTree(createResp).get("id").asText();

        mockMvc.perform(get("/api/v1/expenses/{id}", expId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectViewerWrite() throws Exception {
        String suffix = "view2-exp-" + System.nanoTime();
        String viewerEmail = "viewer2.exp." + suffix + "@example.com";
        String viewerJson = """
                {"firstName":"Viewer2","lastName":"User","email":"%s","password":"SecurePass123"}
                """.formatted(viewerEmail);
        String viewerResp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(viewerJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String viewerUserId = objectMapper.readTree(viewerResp).get("id").asText();
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", orgId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"VIEWER"}
                                """.formatted(viewerUserId)))
                .andExpect(status().isCreated());
        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"SecurePass123"}
                                """.formatted(viewerEmail)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewerToken = objectMapper.readTree(loginResp).get("accessToken").asText();

        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "250.00", "Viewer write", "REF-VIEWWRITE");
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn401WhenMissingAuth() throws Exception {
        mockMvc.perform(get("/api/v1/expenses"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "100.00", "No auth", "REF-NOAUTH")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldPreventCrossTenantRetrieval() throws Exception {
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "300.00", "Cross retrieve", "REF-CROSSR");
        String resp = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String expId = objectMapper.readTree(resp).get("id").asText();

        String suffix = "cross-exp-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Other", "Cross",
                "cross.exp." + suffix + "@example.com",
                "SecurePass123",
                "CrossOrgExp-" + suffix,
                "Legal Cross " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/expenses/{id}", expId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldPreventCrossTenantUpdate() throws Exception {
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "310.00", "Cross update", "REF-CROSSU");
        String resp = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String expId = objectMapper.readTree(resp).get("id").asText();

        String suffix = "crossu-exp-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Other", "CrossU",
                "cross.u." + suffix + "@example.com",
                "SecurePass123",
                "CrossOrgU-" + suffix,
                "Legal Cross U " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();

        mockMvc.perform(put("/api/v1/expenses/{id}", expId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Hacked\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldPreventCrossTenantRecord() throws Exception {
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "320.00", "Cross record", "REF-CROSSREC");
        String resp = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String expId = objectMapper.readTree(resp).get("id").asText();

        String suffix = "crossr-exp-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Other", "CrossR",
                "cross.r." + suffix + "@example.com",
                "SecurePass123",
                "CrossOrgR-" + suffix,
                "Legal Cross R " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();

        mockMvc.perform(post("/api/v1/expenses/{id}/record", expId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldPreventCrossTenantVoid() throws Exception {
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "330.00", "Cross void", "REF-CROSSVOID");
        String resp = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String expId = objectMapper.readTree(resp).get("id").asText();

        String suffix = "crossv-exp-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Other", "CrossV",
                "cross.v." + suffix + "@example.com",
                "SecurePass123",
                "CrossOrgV-" + suffix,
                "Legal Cross V " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();

        mockMvc.perform(delete("/api/v1/expenses/{id}", expId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldListOnlyOwnOrganizationExpenses() throws Exception {
        // create expense in own org
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "400.00", "Own list", "REF-LIST");
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());

        String suffix = "listother-exp-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "List", "Other",
                "list.other." + suffix + "@example.com",
                "SecurePass123",
                "ListOtherOrg-" + suffix,
                "Legal List " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        // other org creates its own expense
        String otherAcc1 = createAccountWithToken(otherToken, "5000L" + System.nanoTime() % 10000, "Exp Other", "EXPENSE");
        String otherAcc2 = createAccountWithToken(otherToken, "1000L" + System.nanoTime() % 10000, "Pay Other", "ASSET");
        String otherVendor = createVendorWithToken(otherToken, "OtherVendor-" + suffix);
        String otherJson = """
                {
                    "vendorId":"%s",
                    "expenseAccountId":"%s",
                    "paymentAccountId":"%s",
                    "amount":50.00,
                    "expenseDate":"%s",
                    "description":"Other org expense",
                    "reference":"REF-OTHER"
                }
                """.formatted(otherVendor, otherAcc1, otherAcc2, LocalDate.now().toString());
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otherJson))
                .andExpect(status().isCreated());

        // own list should not contain other's reference
        mockMvc.perform(get("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .param("reference", "REF-OTHER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.reference=='REF-OTHER')]").doesNotExist());
    }

    @Test
    void shouldFilterByStatus() throws Exception {
        String jsonDraft = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "500.00", "Status Draft", "REF-STAT-DRAFT-" + System.nanoTime());
        String draftResp = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonDraft))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String draftId = objectMapper.readTree(draftResp).get("id").asText();

        String jsonRec = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "600.00", "Status Rec", "REF-STAT-REC-" + System.nanoTime());
        String recResp = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRec))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String recId = objectMapper.readTree(recResp).get("id").asText();
        mockMvc.perform(post("/api/v1/expenses/{id}/record", recId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + draftId + "')]").exists());
        mockMvc.perform(get("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .param("status", "RECORDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + recId + "')]").exists());
    }

    @Test
    void shouldFilterByVendor() throws Exception {
        String v2 = createVendor("VendorFilter-" + System.nanoTime());
        String json1 = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "700.00", "Vendor filter 1", "REF-VF1-" + System.nanoTime());
        String json2 = createExpenseJson(v2, expenseAccountId, paymentAccountId, "800.00", "Vendor filter 2", "REF-VF2-" + System.nanoTime());
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json1))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json2))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .param("vendorId", vendorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.vendorId=='" + vendorId + "')]").exists());
    }

    @Test
    void shouldFilterByExpenseAccount() throws Exception {
        String newAcc = createAccount("5001-" + System.nanoTime() % 10000, "Exp Acc Filter", "EXPENSE");
        String json1 = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "900.00", "Acc filter", "REF-ACC1-" + System.nanoTime());
        String json2 = createExpenseJson(vendorId, newAcc, paymentAccountId, "950.00", "Acc filter2", "REF-ACC2-" + System.nanoTime());
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json1))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json2))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .param("expenseAccountId", expenseAccountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.expenseAccountId=='" + expenseAccountId + "')]").exists());
    }

    @Test
    void shouldFilterByDate() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        String jsonToday = """
                {
                    "vendorId":"%s",
                    "expenseAccountId":"%s",
                    "paymentAccountId":"%s",
                    "amount":100.00,
                    "expenseDate":"%s",
                    "description":"Today",
                    "reference":"REF-DATE-TODAY-%s"
                }
                """.formatted(vendorId, expenseAccountId, paymentAccountId, today.toString(), System.nanoTime());
        String jsonYesterday = """
                {
                    "vendorId":"%s",
                    "expenseAccountId":"%s",
                    "paymentAccountId":"%s",
                    "amount":100.00,
                    "expenseDate":"%s",
                    "description":"Yesterday",
                    "reference":"REF-DATE-YEST-%s"
                }
                """.formatted(vendorId, expenseAccountId, paymentAccountId, yesterday.toString(), System.nanoTime());
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonToday))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonYesterday))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .param("expenseDate", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.expenseDate=='" + today.toString() + "')]").exists());
    }

    @Test
    void shouldPaginate() throws Exception {
        for (int i = 0; i < 3; i++) {
            String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, String.valueOf(100 + i), "Paginate " + i, "REF-PAGE-" + System.nanoTime() + i);
            mockMvc.perform(post("/api/v1/expenses")
                            .header("Authorization", authHeader())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(get("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.size").value(2));
    }

    @Test
    void shouldSearchByReference() throws Exception {
        String ref = "REF-SEARCH-" + System.nanoTime();
        String json = createExpenseJson(vendorId, expenseAccountId, paymentAccountId, "120.00", "Search ref", ref);
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .param("reference", ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.reference=='" + ref + "')]").exists());
    }

    @Test
    void shouldEnsureJournalEntryRegression() throws Exception {
        // create a simple journal entry should still work after expense
        String accCode1 = "JR" + System.nanoTime() % 10000;
        String accCode2 = "JR" + (System.nanoTime() % 10000 + 1);
        String accId1 = createAccount(accCode1, "Journal Cash", "ASSET");
        String accId2 = createAccount(accCode2, "Journal Rev", "REVENUE");
        String journalJson = """
                {
                    "entryNumber":"JE-REG-%s",
                    "entryDate":"%s",
                    "description":"Regression",
                    "reference":"REF-REG",
                    "lines":[
                        {"accountId":"%s","debitAmount":200,"creditAmount":0},
                        {"accountId":"%s","debitAmount":0,"creditAmount":200}
                    ]
                }
                """.formatted(System.nanoTime(), LocalDate.now().toString(), accId1, accId2);
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(journalJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }
}
