package com.example.accounting.controller;

import com.example.accounting.dto.RegisterRequest;
import com.example.accounting.repository.AccountRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvoiceControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AccountRepository accountRepository;

    private String ownerToken;
    private String ownerUserId;
    private String orgId;
    private String customerId;
    private String revenueAccountId;
    private String revenueAccountId2;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = "inv-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Invoice", "Owner",
                "invoice.owner." + suffix + "@example.com",
                "SecurePass123",
                "InvoiceOrg-" + suffix,
                "Legal Invoice " + suffix,
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

        // create customer
        String custJson = """
                {"name":"Customer %s","email":"cust%s@example.com"}
                """.formatted(suffix, suffix.replace("-", ""));
        String custResp = mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(custJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        customerId = objectMapper.readTree(custResp).get("id").asText();

        // create revenue accounts
        revenueAccountId = createAccount("4000-" + suffix.substring(0, 8), "Sales Revenue", "REVENUE");
        revenueAccountId2 = createAccount("4001-" + suffix.substring(0, 8), "Service Revenue", "REVENUE");
        // ensure AR exists via creating invoice later will auto-create, but also create a dummy AR for validation
        createAccount("1100-" + suffix.substring(0, 8), "Accounts Receivable", "ASSET");
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

    private String invoiceJson(String number, String custId, String lines) {
        return """
                {
                    "customerId":"%s",
                    "invoiceNumber":"%s",
                    "invoiceDate":"%s",
                    "dueDate":"%s",
                    "notes":"Notes",
                    "terms":"Terms",
                    "lines": %s
                }
                """.formatted(custId, number, LocalDate.now().toString(), LocalDate.now().plusDays(10).toString(), lines);
    }

    private String validLines() {
        return """
                [
                    {"description":"Item1","quantity":2,"unitPrice":100.00,"taxRate":10,"discountAmount":5.00,"revenueAccountId":"%s"},
                    {"description":"Item2","quantity":1,"unitPrice":50.00,"taxRate":5,"discountAmount":0,"revenueAccountId":"%s"}
                ]
                """.formatted(revenueAccountId, revenueAccountId2);
    }

    // Creation tests
    @Test
    void shouldCreateDraftInvoice() throws Exception {
        String number = "INV-" + System.nanoTime();
        String json = invoiceJson(number, customerId, validLines());
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.invoiceNumber").value(number))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.subtotal").isNumber())
                .andExpect(jsonPath("$.totalAmount").isNumber())
                .andExpect(jsonPath("$.balanceDue").isNumber())
                .andExpect(jsonPath("$.lines.length()").value(2));
    }

    @Test
    void shouldRejectMissingCustomer() throws Exception {
        String json = """
                {
                    "invoiceNumber":"INV-MISS-%s",
                    "invoiceDate":"%s",
                    "lines":[{"description":"Item","quantity":1,"unitPrice":100,"taxRate":10,"discountAmount":0,"revenueAccountId":"%s"}]
                }
                """.formatted(System.nanoTime(), LocalDate.now().toString(), revenueAccountId);
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectCrossTenantCustomer() throws Exception {
        String suffix = "cross-cust-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Other", "Cust",
                "other.cust." + suffix + "@example.com",
                "SecurePass123",
                "OtherOrg-" + suffix,
                "Legal Other " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherCustId = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/customers")
                                .header("Authorization", "Bearer " + objectMapper.readTree(otherResp).get("accessToken").asText())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"Other Customer"}
                                        """))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString()
        ).get("id").asText();

        String json = invoiceJson("INV-CROSSCUST-" + System.nanoTime(), otherCustId, validLines());
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("different organization")));
    }

    @Test
    void shouldRejectInactiveCustomer() throws Exception {
        String custJson = """
                {"name":"InactiveCust %s","email":"inactive%s@example.com"}
                """.formatted(System.nanoTime(), System.nanoTime());
        String custResp = mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(custJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String custId = objectMapper.readTree(custResp).get("id").asText();
        // deactivate
        mockMvc.perform(delete("/api/v1/customers/{id}", custId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isNoContent());
        String json = invoiceJson("INV-INACTCUST-" + System.nanoTime(), custId, validLines());
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("inactive")));
    }

    @Test
    void shouldRejectDuplicateInvoiceNumber() throws Exception {
        String number = "INV-DUP-" + System.nanoTime();
        String json = invoiceJson(number, customerId, validLines());
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldAllowSameNumberInDifferentOrgs() throws Exception {
        String number = "INV-SHARED-" + System.nanoTime();
        String json = invoiceJson(number, customerId, validLines());
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());

        String suffix = "shared-inv-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Shared", "Inv",
                "shared.inv." + suffix + "@example.com",
                "SecurePass123",
                "SharedInvOrg-" + suffix,
                "Legal Shared " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        String otherCustId = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/customers")
                                .header("Authorization", "Bearer " + otherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"Other Cust"}
                                        """))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString()
        ).get("id").asText();
        String otherAcc = createAccountWithToken(otherToken, "4000S" + System.nanoTime() % 10000, "Rev Other", "REVENUE");
        String otherAcc2 = createAccountWithToken(otherToken, "4001S" + System.nanoTime() % 10000, "Rev Other2", "REVENUE");
        String otherLines = """
                [
                    {"description":"Item","quantity":1,"unitPrice":100,"taxRate":10,"discountAmount":0,"revenueAccountId":"%s"},
                    {"description":"Item2","quantity":1,"unitPrice":50,"taxRate":5,"discountAmount":0,"revenueAccountId":"%s"}
                ]
                """.formatted(otherAcc, otherAcc2);
        String otherJson = """
                {
                    "customerId":"%s",
                    "invoiceNumber":"%s",
                    "invoiceDate":"%s",
                    "lines": %s
                }
                """.formatted(otherCustId, number, LocalDate.now().toString(), otherLines);
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otherJson))
                .andExpect(status().isCreated());
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
    void shouldRejectMissingLines() throws Exception {
        String json = """
                {
                    "customerId":"%s",
                    "invoiceNumber":"INV-NOLINES-%s",
                    "invoiceDate":"%s",
                    "lines":[]
                }
                """.formatted(customerId, System.nanoTime(), LocalDate.now().toString());
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectInvalidQuantity() throws Exception {
        String lines = """
                [
                    {"description":"Item","quantity":0,"unitPrice":100,"taxRate":10,"discountAmount":0,"revenueAccountId":"%s"},
                    {"description":"Item2","quantity":1,"unitPrice":50,"taxRate":0,"discountAmount":0,"revenueAccountId":"%s"}
                ]
                """.formatted(revenueAccountId, revenueAccountId2);
        String json = invoiceJson("INV-BADQTY-" + System.nanoTime(), customerId, lines);
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['lines[0].quantity']").exists());
    }

    @Test
    void shouldRejectInvalidPrice() throws Exception {
        String lines = """
                [
                    {"description":"Item","quantity":1,"unitPrice":-10,"taxRate":10,"discountAmount":0,"revenueAccountId":"%s"},
                    {"description":"Item2","quantity":1,"unitPrice":50,"taxRate":0,"discountAmount":0,"revenueAccountId":"%s"}
                ]
                """.formatted(revenueAccountId, revenueAccountId2);
        String json = invoiceJson("INV-BADPRICE-" + System.nanoTime(), customerId, lines);
        // Bean validation @DecimalMin will catch negative unitPrice? Our DTO has @DecimalMin(0.00) for unitPrice, so 400
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectInvalidTaxRate() throws Exception {
        String lines = """
                [
                    {"description":"Item","quantity":1,"unitPrice":100,"taxRate":150,"discountAmount":0,"revenueAccountId":"%s"},
                    {"description":"Item2","quantity":1,"unitPrice":50,"taxRate":0,"discountAmount":0,"revenueAccountId":"%s"}
                ]
                """.formatted(revenueAccountId, revenueAccountId2);
        String json = invoiceJson("INV-BADTAX-" + System.nanoTime(), customerId, lines);
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("taxRate")));
    }

    @Test
    void shouldRejectInvalidDiscount() throws Exception {
        String lines = """
                [
                    {"description":"Item","quantity":1,"unitPrice":100,"taxRate":10,"discountAmount":200,"revenueAccountId":"%s"},
                    {"description":"Item2","quantity":1,"unitPrice":50,"taxRate":0,"discountAmount":0,"revenueAccountId":"%s"}
                ]
                """.formatted(revenueAccountId, revenueAccountId2);
        String json = invoiceJson("INV-BAD DISC-" + System.nanoTime(), customerId, lines);
        // we use invoiceNumber with space to test validation, but discount > subtotal should be 400
        String validNumber = "INV-BADDISC-" + System.nanoTime();
        String validLines = """
                [
                    {"description":"Item","quantity":1,"unitPrice":100,"taxRate":10,"discountAmount":150,"revenueAccountId":"%s"},
                    {"description":"Item2","quantity":1,"unitPrice":50,"taxRate":0,"discountAmount":0,"revenueAccountId":"%s"}
                ]
                """.formatted(revenueAccountId, revenueAccountId2);
        String validJson = invoiceJson(validNumber, customerId, validLines);
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("discount")));
    }

    @Test
    void shouldRejectInvalidRevenueAccount() throws Exception {
        String fakeAcc = java.util.UUID.randomUUID().toString();
        String lines = """
                [
                    {"description":"Item","quantity":1,"unitPrice":100,"taxRate":10,"discountAmount":0,"revenueAccountId":"%s"},
                    {"description":"Item2","quantity":1,"unitPrice":50,"taxRate":0,"discountAmount":0,"revenueAccountId":"%s"}
                ]
                """.formatted(fakeAcc, revenueAccountId);
        String json = invoiceJson("INV-BADACC-" + System.nanoTime(), customerId, lines);
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectInactiveRevenueAccount() throws Exception {
        String accCode = "INACTR" + System.nanoTime() % 10000;
        String accResp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Inactive Rev","type":"REVENUE"}
                                """.formatted(accCode)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String accId = objectMapper.readTree(accResp).get("id").asText();
        mockMvc.perform(put("/api/v1/accounts/{id}", accId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());
        String lines = """
                [
                    {"description":"Item","quantity":1,"unitPrice":100,"taxRate":10,"discountAmount":0,"revenueAccountId":"%s"},
                    {"description":"Item2","quantity":1,"unitPrice":50,"taxRate":0,"discountAmount":0,"revenueAccountId":"%s"}
                ]
                """.formatted(accId, revenueAccountId);
        String json = invoiceJson("INV-INACT-" + System.nanoTime(), customerId, lines);
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("inactive")));
    }

    @Test
    void shouldRejectCrossTenantRevenueAccount() throws Exception {
        String suffix = "cross-acc-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Other", "Cross",
                "cross.acc." + suffix + "@example.com",
                "SecurePass123",
                "CrossOrgAcc-" + suffix,
                "Legal Cross " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        String otherAccId = createAccountWithToken(otherToken, "CROSSR" + System.nanoTime() % 10000, "Cross Rev", "REVENUE");
        String lines = """
                [
                    {"description":"Item","quantity":1,"unitPrice":100,"taxRate":10,"discountAmount":0,"revenueAccountId":"%s"},
                    {"description":"Item2","quantity":1,"unitPrice":50,"taxRate":0,"discountAmount":0,"revenueAccountId":"%s"}
                ]
                """.formatted(otherAccId, revenueAccountId);
        String json = invoiceJson("INV-CROSSACC-" + System.nanoTime(), customerId, lines);
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("different organization")));
    }

    @Test
    void shouldRejectNonRevenueAccount() throws Exception {
        String assetCode = "ASSET" + System.nanoTime() % 10000;
        String assetResp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Asset Acc","type":"ASSET"}
                                """.formatted(assetCode)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String assetId = objectMapper.readTree(assetResp).get("id").asText();
        String lines = """
                [
                    {"description":"Item","quantity":1,"unitPrice":100,"taxRate":10,"discountAmount":0,"revenueAccountId":"%s"},
                    {"description":"Item2","quantity":1,"unitPrice":50,"taxRate":0,"discountAmount":0,"revenueAccountId":"%s"}
                ]
                """.formatted(assetId, revenueAccountId);
        String json = invoiceJson("INV-NONREV-" + System.nanoTime(), customerId, lines);
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("REVENUE")));
    }

    // Calculations
    @Test
    void shouldCalculateCorrectly() throws Exception {
        // Item1: qty 2 * 100 = 200 subtotal, discount 5 => taxable 195 *10% => 19.5 tax, total 214.5
        // Item2: qty 1 * 50 = 50, discount 0 => taxable 50 *5% => 2.5, total 52.5
        // Invoice: subtotal 250, discount 5, tax 22, total 267
        String json = """
                {
                    "customerId":"%s",
                    "invoiceNumber":"INV-CALC-%s",
                    "invoiceDate":"%s",
                    "lines":[
                        {"description":"Item1","quantity":2,"unitPrice":100.00,"taxRate":10,"discountAmount":5.00,"revenueAccountId":"%s"},
                        {"description":"Item2","quantity":1,"unitPrice":50.00,"taxRate":5,"discountAmount":0,"revenueAccountId":"%s"}
                    ]
                }
                """.formatted(customerId, System.nanoTime(), LocalDate.now().toString(), revenueAccountId, revenueAccountId2);
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value(250.00))
                .andExpect(jsonPath("$.discountAmount").value(5.00))
                .andExpect(jsonPath("$.taxAmount").value(22.00))
                .andExpect(jsonPath("$.totalAmount").value(267.00))
                .andExpect(jsonPath("$.balanceDue").value(267.00))
                .andExpect(jsonPath("$.amountPaid").value(0))
                .andExpect(jsonPath("$.lines[0].lineSubtotal").value(200.00))
                .andExpect(jsonPath("$.lines[0].lineTaxAmount").value(19.50))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(214.50));
    }

    @Test
    void shouldHandleMultipleLinesAndRevenueAccounts() throws Exception {
        String lines = """
                [
                    {"description":"Item1","quantity":1,"unitPrice":100,"taxRate":10,"discountAmount":0,"revenueAccountId":"%s"},
                    {"description":"Item2","quantity":2,"unitPrice":50,"taxRate":5,"discountAmount":5,"revenueAccountId":"%s"}
                ]
                """.formatted(revenueAccountId, revenueAccountId2);
        String json = invoiceJson("INV-MULTI-" + System.nanoTime(), customerId, lines);
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].revenueAccountId").value(revenueAccountId))
                .andExpect(jsonPath("$.lines[1].revenueAccountId").value(revenueAccountId2));
    }

    // Lifecycle
    @Test
    void shouldUpdateDraft() throws Exception {
        String json = invoiceJson("INV-UPD-" + System.nanoTime(), customerId, validLines());
        String resp = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        String updateJson = """
                {"notes":"Updated notes","terms":"Updated terms"}
                """;
        mockMvc.perform(put("/api/v1/invoices/{id}", id)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("Updated notes"));
    }

    @Test
    void shouldIssueDraft() throws Exception {
        String json = invoiceJson("INV-ISSUE-" + System.nanoTime(), customerId, validLines());
        String resp = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        mockMvc.perform(post("/api/v1/invoices/{id}/issue", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.journalEntryId").isNotEmpty());
    }

    @Test
    void shouldNotUpdateIssued() throws Exception {
        String json = invoiceJson("INV-NOUPD-" + System.nanoTime(), customerId, validLines());
        String resp = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        mockMvc.perform(post("/api/v1/invoices/{id}/issue", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/invoices/{id}", id)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"Hacked\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldNotIssueIssuedAgain() throws Exception {
        String json = invoiceJson("INV-REISSUE-" + System.nanoTime(), customerId, validLines());
        String resp = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        mockMvc.perform(post("/api/v1/invoices/{id}/issue", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/invoices/{id}/issue", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldVoidDraft() throws Exception {
        String json = invoiceJson("INV-VOID-" + System.nanoTime(), customerId, validLines());
        String resp = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        mockMvc.perform(delete("/api/v1/invoices/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"));
    }

    @Test
    void shouldNotVoidIssued() throws Exception {
        String json = invoiceJson("INV-NOVOID-" + System.nanoTime(), customerId, validLines());
        String resp = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        mockMvc.perform(post("/api/v1/invoices/{id}/issue", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/invoices/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldNotModifyVoid() throws Exception {
        String json = invoiceJson("INV-VOIDMOD-" + System.nanoTime(), customerId, validLines());
        String resp = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        mockMvc.perform(delete("/api/v1/invoices/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/invoices/{id}", id)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"Hacked\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/invoices/{id}/issue", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isConflict());
    }

    // Accounting
    @Test
    void shouldCreateJournalOnIssue() throws Exception {
        String json = invoiceJson("INV-JOURNAL-" + System.nanoTime(), customerId, validLines());
        String resp = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        String issueResp = mockMvc.perform(post("/api/v1/invoices/{id}/issue", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journalEntryId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String journalId = objectMapper.readTree(issueResp).get("journalEntryId").asText();
        // fetch journal and verify
        mockMvc.perform(get("/api/v1/journal-entries/{id}", journalId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.totalDebits").isNumber())
                .andExpect(jsonPath("$.totalCredits").isNumber());
        // verify balanced
        String journalJson = mockMvc.perform(get("/api/v1/journal-entries/{id}", journalId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        double debits = objectMapper.readTree(journalJson).get("totalDebits").asDouble();
        double credits = objectMapper.readTree(journalJson).get("totalCredits").asDouble();
        org.assertj.core.api.Assertions.assertThat(debits).isEqualTo(credits);
    }

    @Test
    void shouldUseArDebitAndRevenueCredit() throws Exception {
        String json = invoiceJson("INV-ARREV-" + System.nanoTime(), customerId, validLines());
        String resp = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        String issueResp = mockMvc.perform(post("/api/v1/invoices/{id}/issue", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String journalId = objectMapper.readTree(issueResp).get("journalEntryId").asText();
        String journal = mockMvc.perform(get("/api/v1/journal-entries/{id}", journalId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode lines = objectMapper.readTree(journal).get("lines");
        boolean hasArDebit = false;
        boolean hasRevenueCredit = false;
        for (JsonNode line : lines) {
            if (line.get("debitAmount").asDouble() > 0) hasArDebit = true;
            if (line.get("creditAmount").asDouble() > 0) hasRevenueCredit = true;
        }
        org.assertj.core.api.Assertions.assertThat(hasArDebit).isTrue();
        org.assertj.core.api.Assertions.assertThat(hasRevenueCredit).isTrue();
    }

    @Test
    void shouldRollbackOnAccountingFailure() throws Exception {
        // Create an AR account then deactivate it before posting to cause failure
        String arCode = "1100-ROLL" + System.nanoTime() % 10000;
        String arResp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"AR Rollback","type":"ASSET"}
                                """.formatted(arCode)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String arId = objectMapper.readTree(arResp).get("id").asText();
        // make it inactive after invoice creation
        String json = invoiceJson("INV-ROLLBACK-" + System.nanoTime(), customerId, validLines());
        String resp = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        // deactivate AR before issue - but our issue re-resolves AR via system account, not this specific AR?
        // Instead, test with inactive revenue account: create invoice with valid accounts, then deactivate one revenue account before posting
        String revCode = "4000-ROLL" + System.nanoTime() % 10000;
        String revResp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Rev Rollback","type":"REVENUE"}
                                """.formatted(revCode)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String revId = objectMapper.readTree(revResp).get("id").asText();
        String json2 = """
                {
                    "customerId":"%s",
                    "invoiceNumber":"INV-ROLL-%s",
                    "invoiceDate":"%s",
                    "lines":[
                        {"description":"Item","quantity":1,"unitPrice":100,"taxRate":10,"discountAmount":0,"revenueAccountId":"%s"}
                    ]
                }
                """.formatted(customerId, System.nanoTime(), java.time.LocalDate.now().toString(), revId);
        // need at least 2 lines? Actually our invoice requires at least 1 line, but journal needs 2? No, invoice needs 1 line, journal will create 2 lines (AR + revenue)
        // So single line invoice is allowed? The spec says invoice must have at least one line before issuing, so single line is okay
        // But our validLines has 2 lines, so we need to test rollback with inactive account after draft creation
        String jsonValid = """
                {
                    "customerId":"%s",
                    "invoiceNumber":"INV-ROLL2-%s",
                    "invoiceDate":"%s",
                    "lines":[
                        {"description":"Item","quantity":1,"unitPrice":100,"taxRate":0,"discountAmount":0,"revenueAccountId":"%s"}
                    ]
                }
                """.formatted(customerId, System.nanoTime(), java.time.LocalDate.now().toString(), revId);
        String resp2 = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonValid))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id2 = objectMapper.readTree(resp2).get("id").asText();
        // deactivate revenue account
        mockMvc.perform(put("/api/v1/accounts/{id}", revId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());
        // try to issue - should fail and remain DRAFT
        mockMvc.perform(post("/api/v1/invoices/{id}/issue", id2)
                        .header("Authorization", authHeader()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/invoices/{id}", id2)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.journalEntryId").doesNotExist());
        // ensure no journal was created with that reference? Check via list
        mockMvc.perform(get("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .param("reference", "INV-ROLL2-" + id2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // Authorization
    @Test
    void shouldAllowOwnerWrite() throws Exception {
        String json = invoiceJson("INV-OWNER-" + System.nanoTime(), customerId, validLines());
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldRejectEmployeeWrite() throws Exception {
        String suffix = "emp-inv-" + System.nanoTime();
        String empEmail = "emp.inv." + suffix + "@example.com";
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
        String json = invoiceJson("INV-EMP-" + System.nanoTime(), customerId, validLines());
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", "Bearer " + empToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectViewerWrite() throws Exception {
        String suffix = "view-inv-" + System.nanoTime();
        String viewerEmail = "viewer.inv." + suffix + "@example.com";
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
        String json = invoiceJson("INV-VIEWER-" + System.nanoTime(), customerId, validLines());
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn401WhenMissingAuth() throws Exception {
        mockMvc.perform(get("/api/v1/invoices"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invoiceJson("INV-NOAUTH-" + System.nanoTime(), customerId, validLines())))
                .andExpect(status().isUnauthorized());
    }

    // Tenant isolation
    @Test
    void shouldPreventCrossTenantRetrieval() throws Exception {
        String json = invoiceJson("INV-CROSSR-" + System.nanoTime(), customerId, validLines());
        String resp = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        String suffix = "cross-inv-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Cross", "Inv",
                "cross.inv." + suffix + "@example.com",
                "SecurePass123",
                "CrossInvOrg-" + suffix,
                "Legal Cross " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        mockMvc.perform(get("/api/v1/invoices/{id}", id)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldPreventCrossTenantUpdateAndIssueAndVoid() throws Exception {
        String json = invoiceJson("INV-CROSSUD-" + System.nanoTime(), customerId, validLines());
        String resp = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        String suffix = "cross-ud-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Cross", "UD",
                "cross.ud." + suffix + "@example.com",
                "SecurePass123",
                "CrossUDOrg-" + suffix,
                "Legal Cross UD " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        mockMvc.perform(put("/api/v1/invoices/{id}", id)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"Hacked\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/invoices/{id}/issue", id)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/invoices/{id}", id)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldListOnlyOwnOrganizationInvoices() throws Exception {
        String ownNumber = "INV-LIST-" + System.nanoTime();
        String json = invoiceJson(ownNumber, customerId, validLines());
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());
        String suffix = "listother-" + System.nanoTime();
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
        mockMvc.perform(get("/api/v1/invoices")
                        .header("Authorization", "Bearer " + otherToken)
                        .param("invoiceNumber", ownNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/invoices")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.invoiceNumber=='" + ownNumber + "')]").doesNotExist());
    }
}
