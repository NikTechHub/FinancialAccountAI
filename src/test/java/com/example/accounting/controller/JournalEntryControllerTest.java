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
class JournalEntryControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AccountRepository accountRepository;

    private String ownerToken;
    private String ownerUserId;
    private String orgId;
    private String assetAccountId;
    private String revenueAccountId;
    private String expenseAccountId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = "je-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Journal", "Owner",
                "journal.owner." + suffix + "@example.com",
                "SecurePass123",
                "JournalOrg-" + suffix,
                "Legal Journal " + suffix,
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

        // create 3 accounts for testing - use short codes to respect 20 char limit
        String shortSuffix = String.valueOf(Math.abs(suffix.hashCode() % 10000));
        assetAccountId = createAccount("1000-" + shortSuffix, "Cash", "ASSET");
        revenueAccountId = createAccount("4000-" + shortSuffix, "Sales Revenue", "REVENUE");
        expenseAccountId = createAccount("5000-" + shortSuffix, "Expenses", "EXPENSE");
    }

    private String authHeader() {
        return "Bearer " + ownerToken;
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

    private String buildJournalJson(String entryNumber, String description, String reference, String linesJson) {
        return """
                {
                    "entryNumber":"%s",
                    "entryDate":"%s",
                    "description":"%s",
                    "reference":"%s",
                    "lines": %s
                }
                """.formatted(entryNumber, LocalDate.now().toString(), description, reference, linesJson);
    }

    private String balancedLines(String acc1, String acc2) {
        return """
                [
                    {"accountId":"%s","debitAmount":1000.00,"creditAmount":0},
                    {"accountId":"%s","debitAmount":0,"creditAmount":1000.00}
                ]
                """.formatted(acc1, acc2);
    }

    // 1. Create valid balanced journal entry
    @Test
    void shouldCreateValidBalancedJournalEntry() throws Exception {
        String entryNumber = "JE-" + System.nanoTime();
        String json = buildJournalJson(entryNumber, "Cash sale", "REF-001", balancedLines(assetAccountId, revenueAccountId));
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.entryNumber").value(entryNumber))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.totalDebits").value(1000.00))
                .andExpect(jsonPath("$.totalCredits").value(1000.00))
                .andExpect(jsonPath("$.lines.length()").value(2));
    }

    // 2. Create draft
    @Test
    void shouldCreateDraftJournalEntry() throws Exception {
        String json = buildJournalJson("JE-DRAFT-" + System.nanoTime(), "Draft entry", "REF-DRAFT", balancedLines(assetAccountId, revenueAccountId));
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    // 3. Retrieve
    @Test
    void shouldRetrieveJournalEntry() throws Exception {
        String entryNumber = "JE-GET-" + System.nanoTime();
        String createResp = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildJournalJson(entryNumber, "Get test", "REF-GET", balancedLines(assetAccountId, revenueAccountId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResp).get("id").asText();
        mockMvc.perform(get("/api/v1/journal-entries/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.entryNumber").value(entryNumber));
    }

    // 4. Update draft
    @Test
    void shouldUpdateDraftJournalEntry() throws Exception {
        String createResp = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildJournalJson("JE-UPD-" + System.nanoTime(), "Before", "REF-BEFORE", balancedLines(assetAccountId, revenueAccountId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResp).get("id").asText();
        String updateJson = """
                {"description":"Updated description","reference":"REF-UPDATED"}
                """;
        mockMvc.perform(put("/api/v1/journal-entries/{id}", id)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Updated description"))
                .andExpect(jsonPath("$.reference").value("REF-UPDATED"));
    }

    // 5. Post balanced
    @Test
    void shouldPostBalancedJournalEntry() throws Exception {
        String createResp = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildJournalJson("JE-POST-" + System.nanoTime(), "Post test", "REF-POST", balancedLines(assetAccountId, revenueAccountId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResp).get("id").asText();
        mockMvc.perform(post("/api/v1/journal-entries/{id}/post", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));
    }

    // 6. Reject unbalanced
    @Test
    void shouldRejectUnbalancedJournalEntry() throws Exception {
        String createResp = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildJournalJson("JE-UNBAL-" + System.nanoTime(), "Unbalanced", "REF-UNBAL",
                                """
                                        [
                                            {"accountId":"%s","debitAmount":1000,"creditAmount":0},
                                            {"accountId":"%s","debitAmount":0,"creditAmount":900}
                                        ]
                                        """.formatted(assetAccountId, revenueAccountId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResp).get("id").asText();
        mockMvc.perform(post("/api/v1/journal-entries/{id}/post", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Unbalanced")));
    }

    // 7. Reject debit-only
    @Test
    void shouldRejectDebitOnlyEntry() throws Exception {
        String json = buildJournalJson("JE-DEBITONLY-" + System.nanoTime(), "Debit only", "REF", """
                [
                    {"accountId":"%s","debitAmount":100,"creditAmount":0},
                    {"accountId":"%s","debitAmount":200,"creditAmount":0}
                ]
                """.formatted(assetAccountId, revenueAccountId));
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("at least one debit and one credit")));
    }

    // 8. Reject credit-only
    @Test
    void shouldRejectCreditOnlyEntry() throws Exception {
        String json = buildJournalJson("JE-CREDITONLY-" + System.nanoTime(), "Credit only", "REF", """
                [
                    {"accountId":"%s","debitAmount":0,"creditAmount":100},
                    {"accountId":"%s","debitAmount":0,"creditAmount":200}
                ]
                """.formatted(assetAccountId, revenueAccountId));
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    // 9. Reject fewer than two lines
    @Test
    void shouldRejectFewerThanTwoLines() throws Exception {
        String json = buildJournalJson("JE-ONELINE-" + System.nanoTime(), "One line", "REF", """
                [
                    {"accountId":"%s","debitAmount":100,"creditAmount":0}
                ]
                """.formatted(assetAccountId));
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.lines").value(org.hamcrest.Matchers.containsString("at least 2 lines")));
    }

    // 10. Reject zero-value line
    @Test
    void shouldRejectZeroValueLine() throws Exception {
        String json = buildJournalJson("JE-ZERO-" + System.nanoTime(), "Zero", "REF", """
                [
                    {"accountId":"%s","debitAmount":0,"creditAmount":0},
                    {"accountId":"%s","debitAmount":0,"creditAmount":0}
                ]
                """.formatted(assetAccountId, revenueAccountId));
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    // 11. Reject negative amount
    @Test
    void shouldRejectNegativeAmount() throws Exception {
        String json = buildJournalJson("JE-NEG-" + System.nanoTime(), "Negative", "REF", """
                [
                    {"accountId":"%s","debitAmount":-100,"creditAmount":0},
                    {"accountId":"%s","debitAmount":0,"creditAmount":100}
                ]
                """.formatted(assetAccountId, revenueAccountId));
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("negative")));
    }

    // 12. Reject both debit and credit on same line
    @Test
    void shouldRejectBothDebitAndCreditOnSameLine() throws Exception {
        String json = buildJournalJson("JE-BOTH-" + System.nanoTime(), "Both", "REF", """
                [
                    {"accountId":"%s","debitAmount":100,"creditAmount":100},
                    {"accountId":"%s","debitAmount":0,"creditAmount":100}
                ]
                """.formatted(assetAccountId, revenueAccountId));
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("either debit or credit, not both")));
    }

    // 13. Reject inactive account
    @Test
    void shouldRejectInactiveAccount() throws Exception {
        String accCode = "INACT" + System.nanoTime() % 10000;
        String accResp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Inactive Acc","type":"ASSET"}
                                """.formatted(accCode)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String accId = objectMapper.readTree(accResp).get("id").asText();
        // deactivate via update
        mockMvc.perform(put("/api/v1/accounts/{id}", accId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());
        // try to use inactive account in journal
        String json = buildJournalJson("JE-INACT-" + System.nanoTime(), "Inactive", "REF", """
                [
                    {"accountId":"%s","debitAmount":100,"creditAmount":0},
                    {"accountId":"%s","debitAmount":0,"creditAmount":100}
                ]
                """.formatted(accId, revenueAccountId));
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("inactive")));
    }

    // 14. Reject missing account
    @Test
    void shouldRejectMissingAccount() throws Exception {
        String fakeAccId = UUID.randomUUID().toString();
        String json = buildJournalJson("JE-MISSACC-" + System.nanoTime(), "Missing", "REF", """
                [
                    {"accountId":"%s","debitAmount":100,"creditAmount":0},
                    {"accountId":"%s","debitAmount":0,"creditAmount":100}
                ]
                """.formatted(fakeAccId, revenueAccountId));
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid account")));
    }

    // 15. Reject cross-tenant account
    @Test
    void shouldRejectCrossTenantAccount() throws Exception {
        String suffix = "cross-acc-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Other", "Cross",
                "cross." + suffix + "@example.com",
                "SecurePass123",
                "CrossOrg-" + suffix,
                "Legal Cross " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        String otherAccId = createAccountWithToken(otherToken, "CROSS" + System.nanoTime() % 10000, "Cross Acc", "ASSET");

        String json = buildJournalJson("JE-CROSS-" + System.nanoTime(), "Cross", "REF", """
                [
                    {"accountId":"%s","debitAmount":100,"creditAmount":0},
                    {"accountId":"%s","debitAmount":0,"creditAmount":100}
                ]
                """.formatted(otherAccId, revenueAccountId));
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("different organization")));
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

    private String createAccountWithToken(String token, String code, String name, String type, String ignored) throws Exception {
        return createAccountWithToken(token, code, name, type);
    }

    // 16. Reject duplicate entry number
    @Test
    void shouldRejectDuplicateEntryNumber() throws Exception {
        String entryNumber = "JE-DUP-" + System.nanoTime();
        String json = buildJournalJson(entryNumber, "Dup", "REF", balancedLines(assetAccountId, revenueAccountId));
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict());
    }

    // 17. Same entry number in different organizations allowed
    @Test
    void shouldAllowSameEntryNumberInDifferentOrganizations() throws Exception {
        String entryNumber = "JE-SHARED-" + System.nanoTime();
        String json = buildJournalJson(entryNumber, "Shared", "REF", balancedLines(assetAccountId, revenueAccountId));
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());

        String suffix = "shared-je-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Shared", "JE",
                "shared.je." + suffix + "@example.com",
                "SecurePass123",
                "SharedJEOrg-" + suffix,
                "Legal Shared " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        String otherAcc1 = createAccountWithToken(otherToken, "1000S" + System.nanoTime() % 10000, "Cash2", "ASSET");
        String otherAcc2 = createAccountWithToken(otherToken, "4000S" + System.nanoTime() % 10000, "Rev2", "REVENUE");
        String otherJson = """
                {
                    "entryNumber":"%s",
                    "entryDate":"%s",
                    "description":"Shared",
                    "reference":"REF",
                    "lines":[
                        {"accountId":"%s","debitAmount":100,"creditAmount":0},
                        {"accountId":"%s","debitAmount":0,"creditAmount":100}
                    ]
                }
                """.formatted(entryNumber, LocalDate.now().toString(), otherAcc1, otherAcc2);
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otherJson))
                .andExpect(status().isCreated());
    }

    // 18. Post draft successfully (already tested but explicit)
    @Test
    void shouldPostDraftSuccessfully() throws Exception {
        String createResp = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildJournalJson("JE-POST2-" + System.nanoTime(), "Post", "REF", balancedLines(assetAccountId, revenueAccountId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResp).get("id").asText();
        mockMvc.perform(post("/api/v1/journal-entries/{id}/post", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));
    }

    // 19. Cannot modify posted entry
    @Test
    void shouldNotModifyPostedEntry() throws Exception {
        String createResp = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildJournalJson("JE-POSTMOD-" + System.nanoTime(), "PostMod", "REF", balancedLines(assetAccountId, revenueAccountId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResp).get("id").asText();
        mockMvc.perform(post("/api/v1/journal-entries/{id}/post", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk());
        String updateJson = """
                {"description":"Hacked"}
                """;
        mockMvc.perform(put("/api/v1/journal-entries/{id}", id)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isConflict());
    }

    // 20. Cannot delete posted entry (void)
    @Test
    void shouldNotVoidPostedEntry() throws Exception {
        String createResp = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildJournalJson("JE-POSTVOID-" + System.nanoTime(), "PostVoid", "REF", balancedLines(assetAccountId, revenueAccountId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResp).get("id").asText();
        mockMvc.perform(post("/api/v1/journal-entries/{id}/post", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/journal-entries/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isConflict());
    }

    // 21. Void draft entry
    @Test
    void shouldVoidDraftEntry() throws Exception {
        String createResp = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildJournalJson("JE-VOID-" + System.nanoTime(), "Void", "REF", balancedLines(assetAccountId, revenueAccountId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResp).get("id").asText();
        mockMvc.perform(delete("/api/v1/journal-entries/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"));
    }

    // 22. Cannot post void entry
    @Test
    void shouldNotPostVoidEntry() throws Exception {
        String createResp = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildJournalJson("JE-VOIDPOST-" + System.nanoTime(), "VoidPost", "REF", balancedLines(assetAccountId, revenueAccountId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResp).get("id").asText();
        mockMvc.perform(delete("/api/v1/journal-entries/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/journal-entries/{id}/post", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isConflict());
    }

    // 23. Cannot modify void entry
    @Test
    void shouldNotModifyVoidEntry() throws Exception {
        String createResp = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildJournalJson("JE-VOIDMOD-" + System.nanoTime(), "VoidMod", "REF", balancedLines(assetAccountId, revenueAccountId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResp).get("id").asText();
        mockMvc.perform(delete("/api/v1/journal-entries/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk());
        String updateJson = """
                {"description":"Hacked Void"}
                """;
        mockMvc.perform(put("/api/v1/journal-entries/{id}", id)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isConflict());
    }

    // 24. Employee read access
    @Test
    void shouldAllowEmployeeRead() throws Exception {
        String suffix = "emp-je-" + System.nanoTime();
        String empEmail = "emp.je." + suffix + "@example.com";
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

        // create entry as owner for read test
        String createResp = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildJournalJson("JE-EMPREAD-" + System.nanoTime(), "Emp Read", "REF", balancedLines(assetAccountId, revenueAccountId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String jeId = objectMapper.readTree(createResp).get("id").asText();

        mockMvc.perform(get("/api/v1/journal-entries/{id}", jeId)
                        .header("Authorization", "Bearer " + empToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/journal-entries")
                        .header("Authorization", "Bearer " + empToken))
                .andExpect(status().isOk());
    }

    // 25. Employee write rejection
    @Test
    void shouldRejectEmployeeWrite() throws Exception {
        String suffix = "emp-je2-" + System.nanoTime();
        String empEmail = "emp2.je." + suffix + "@example.com";
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

        String json = buildJournalJson("JE-EMPWRITE-" + System.nanoTime(), "Emp Write", "REF", balancedLines(assetAccountId, revenueAccountId));
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", "Bearer " + empToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }

    // 26. Viewer read access
    @Test
    void shouldAllowViewerRead() throws Exception {
        String suffix = "view-je-" + System.nanoTime();
        String viewerEmail = "viewer.je." + suffix + "@example.com";
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

        String createResp = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildJournalJson("JE-VIEWERR-" + System.nanoTime(), "Viewer Read", "REF", balancedLines(assetAccountId, revenueAccountId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String jeId = objectMapper.readTree(createResp).get("id").asText();

        mockMvc.perform(get("/api/v1/journal-entries/{id}", jeId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
    }

    // 27. Viewer write rejection
    @Test
    void shouldRejectViewerWrite() throws Exception {
        String suffix = "view-je2-" + System.nanoTime();
        String viewerEmail = "viewer2.je." + suffix + "@example.com";
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

        String json = buildJournalJson("JE-VIEWERW-" + System.nanoTime(), "Viewer Write", "REF", balancedLines(assetAccountId, revenueAccountId));
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }

    // 28. Missing authentication
    @Test
    void shouldReturn401WhenMissingAuth() throws Exception {
        mockMvc.perform(get("/api/v1/journal-entries"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/journal-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildJournalJson("JE-NOAUTH", "No Auth", "REF", balancedLines(assetAccountId, revenueAccountId))))
                .andExpect(status().isUnauthorized());
    }

    // 29-32 Cross-tenant
    @Test
    void shouldPreventCrossTenantAccess() throws Exception {
        String createResp = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildJournalJson("JE-CROSS-" + System.nanoTime(), "Cross", "REF", balancedLines(assetAccountId, revenueAccountId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String jeId = objectMapper.readTree(createResp).get("id").asText();

        String suffix = "cross-je-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Cross", "Tenant",
                "cross.je." + suffix + "@example.com",
                "SecurePass123",
                "CrossJEOrg-" + suffix,
                "Legal Cross " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();

        // retrieval
        mockMvc.perform(get("/api/v1/journal-entries/{id}", jeId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        // update
        mockMvc.perform(put("/api/v1/journal-entries/{id}", jeId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Hacked\"}"))
                .andExpect(status().isNotFound());
        // post
        mockMvc.perform(post("/api/v1/journal-entries/{id}/post", jeId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        // void
        mockMvc.perform(delete("/api/v1/journal-entries/{id}", jeId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        // list should not contain
        mockMvc.perform(get("/api/v1/journal-entries")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // 33. Tenant-safe listing
    @Test
    void shouldListOnlyOwnOrganizationEntries() throws Exception {
        // create 2 entries in own org
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/journal-entries")
                            .header("Authorization", authHeader())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(buildJournalJson("JE-LIST-" + System.nanoTime() + i, "List", "REF" + i, balancedLines(assetAccountId, revenueAccountId))))
                    .andExpect(status().isCreated());
        }
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
        String otherAcc1 = createAccountWithToken(otherToken, "1000L" + System.nanoTime() % 10000, "Cash Other", "ASSET");
        String otherAcc2 = createAccountWithToken(otherToken, "4000L" + System.nanoTime() % 10000, "Rev Other", "REVENUE");
        String otherJson = """
                {
                    "entryNumber":"JE-OTHERLIST-%s",
                    "entryDate":"%s",
                    "description":"Other org entry",
                    "reference":"REF-OTHER",
                    "lines":[
                        {"accountId":"%s","debitAmount":50,"creditAmount":0},
                        {"accountId":"%s","debitAmount":0,"creditAmount":50}
                    ]
                }
                """.formatted(System.nanoTime(), LocalDate.now().toString(), otherAcc1, otherAcc2);
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otherJson))
                .andExpect(status().isCreated());

        // own list should not contain other's entryNumber
        mockMvc.perform(get("/api/v1/journal-entries")
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.entryNumber=='JE-OTHERLIST')]").doesNotExist());
    }

    // 34. Status filtering
    @Test
    void shouldFilterByStatus() throws Exception {
        String draftResp = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildJournalJson("JE-STATDRAFT-" + System.nanoTime(), "Draft", "REF", balancedLines(assetAccountId, revenueAccountId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String draftId = objectMapper.readTree(draftResp).get("id").asText();
        String postedResp = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildJournalJson("JE-STATPOSTED-" + System.nanoTime(), "Posted", "REF", balancedLines(assetAccountId, revenueAccountId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String postedId = objectMapper.readTree(postedResp).get("id").asText();
        mockMvc.perform(post("/api/v1/journal-entries/{id}/post", postedId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + draftId + "')]").exists());
        mockMvc.perform(get("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .param("status", "POSTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + postedId + "')]").exists());
    }

    // 35. Date filtering
    @Test
    void shouldFilterByDate() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        String jsonToday = """
                {
                    "entryNumber":"JE-DATE-" + System.nanoTime(),
                    "entryDate":"%s",
                    "description":"Today",
                    "reference":"REF",
                    "lines":[
                        {"accountId":"%s","debitAmount":10,"creditAmount":0},
                        {"accountId":"%s","debitAmount":0,"creditAmount":10}
                    ]
                }
                """;
        // create today and yesterday via direct JSON
        String todayJson = """
                {
                    "entryNumber":"JE-DATETODAY-%s",
                    "entryDate":"%s",
                    "description":"Today",
                    "lines":[
                        {"accountId":"%s","debitAmount":10,"creditAmount":0},
                        {"accountId":"%s","debitAmount":0,"creditAmount":10}
                    ]
                }
                """.formatted(System.nanoTime(), today.toString(), assetAccountId, revenueAccountId);
        String yestJson = """
                {
                    "entryNumber":"JE-DATEYEST-%s",
                    "entryDate":"%s",
                    "description":"Yesterday",
                    "lines":[
                        {"accountId":"%s","debitAmount":10,"creditAmount":0},
                        {"accountId":"%s","debitAmount":0,"creditAmount":10}
                    ]
                }
                """.formatted(System.nanoTime(), yesterday.toString(), assetAccountId, revenueAccountId);
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(todayJson))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(yestJson))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .param("entryDate", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.entryDate=='" + today.toString() + "')]").exists());
    }

    // 36. Pagination
    @Test
    void shouldPaginate() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/journal-entries")
                            .header("Authorization", authHeader())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(buildJournalJson("JE-PAGE-" + System.nanoTime() + i, "Page", "REF" + i, balancedLines(assetAccountId, revenueAccountId))))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(get("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.size").value(2));
        mockMvc.perform(get("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    // 37-39 Total calculations
    @Test
    void shouldCalculateTotalsCorrectly() throws Exception {
        String json = """
                {
                    "entryNumber":"JE-TOTAL-" + System.nanoTime(),
                    "entryDate":"%s",
                    "description":"Totals",
                    "lines":[
                        {"accountId":"%s","debitAmount":600,"creditAmount":0},
                        {"accountId":"%s","debitAmount":400,"creditAmount":0},
                        {"accountId":"%s","debitAmount":0,"creditAmount":1000}
                    ]
                }
                """.formatted(LocalDate.now().toString(), assetAccountId, expenseAccountId, revenueAccountId);
        // need 3 accounts, but our balancedLines only has 2, so create custom with 3 lines
        String entryNumber = "JE-TOTAL-" + System.nanoTime();
        String customJson = """
                {
                    "entryNumber":"%s",
                    "entryDate":"%s",
                    "description":"Totals",
                    "lines":[
                        {"accountId":"%s","debitAmount":600,"creditAmount":0},
                        {"accountId":"%s","debitAmount":400,"creditAmount":0},
                        {"accountId":"%s","debitAmount":0,"creditAmount":1000}
                    ]
                }
                """.formatted(entryNumber, LocalDate.now().toString(), assetAccountId, expenseAccountId, revenueAccountId);
        String resp = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalDebits").value(1000.0))
                .andExpect(jsonPath("$.totalCredits").value(1000.0))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        mockMvc.perform(post("/api/v1/journal-entries/{id}/post", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDebits").value(1000.0));
    }

    @Test
    void shouldRejectUnbalancedOnPostWithCalculation() throws Exception {
        String entryNumber = "JE-UNBALCALC-" + System.nanoTime();
        String json = """
                {
                    "entryNumber":"%s",
                    "entryDate":"%s",
                    "description":"Unbalanced",
                    "lines":[
                        {"accountId":"%s","debitAmount":1000,"creditAmount":0},
                        {"accountId":"%s","debitAmount":0,"creditAmount":900}
                    ]
                }
                """.formatted(entryNumber, LocalDate.now().toString(), assetAccountId, revenueAccountId);
        String resp = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        mockMvc.perform(post("/api/v1/journal-entries/{id}/post", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isBadRequest());
    }
}
