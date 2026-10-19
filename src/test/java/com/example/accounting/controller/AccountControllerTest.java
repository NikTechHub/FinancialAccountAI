package com.example.accounting.controller;

import com.example.accounting.dto.RegisterRequest;
import com.example.accounting.entity.Account;
import com.example.accounting.repository.AccountRepository;
import com.example.accounting.repository.OrganizationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private String ownerToken;
    private String ownerUserId;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = "acc-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Account", "Owner",
                "account.owner." + suffix + "@example.com",
                "SecurePass123",
                "AccOrg-" + suffix,
                "Legal Acc " + suffix,
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
    }

    private String authHeader() {
        return "Bearer " + ownerToken;
    }

    private String createAccountJson(String code, String name, String type) {
        return """
                {"code":"%s","name":"%s","type":"%s"}
                """.formatted(code, name, type);
    }

    private String createAccountWithParentJson(String code, String name, String type, String parentId) {
        return """
                {"code":"%s","name":"%s","type":"%s","parentAccountId":"%s"}
                """.formatted(code, name, type, parentId);
    }

    @Test
    void shouldCreateAccount() throws Exception {
        String code = "1000-" + System.nanoTime() % 10000;
        String json = createAccountJson(code, "Cash " + System.nanoTime(), "ASSET");
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.organizationId").value(orgId))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.systemAccount").value(false))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void shouldGetAccount() throws Exception {
        String code = "1001-" + System.nanoTime() % 10000;
        String createResp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountJson(code, "Get Account", "ASSET")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResp).get("id").asText();
        mockMvc.perform(get("/api/v1/accounts/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void shouldUpdateAccount() throws Exception {
        String code = "1002-" + System.nanoTime() % 10000;
        String createResp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountJson(code, "Update Me", "EXPENSE")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResp).get("id").asText();
        String updateJson = """
                {"name":"Updated Name","description":"Updated desc"}
                """;
        mockMvc.perform(put("/api/v1/accounts/{id}", id)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.description").value("Updated desc"));
    }

    @Test
    void shouldDeactivateAccount() throws Exception {
        String code = "1003-" + System.nanoTime() % 10000;
        String createResp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountJson(code, "Deactivate Me", "LIABILITY")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResp).get("id").asText();
        mockMvc.perform(delete("/api/v1/accounts/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/accounts/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void shouldListAccounts() throws Exception {
        for (int i = 0; i < 2; i++) {
            String code = "L" + System.nanoTime() % 100000 + i;
            mockMvc.perform(post("/api/v1/accounts")
                            .header("Authorization", authHeader())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createAccountJson(code, "List " + i + " " + System.nanoTime(), "ASSET")))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber());
    }

    @Test
    void shouldPaginate() throws Exception {
        for (int i = 0; i < 5; i++) {
            String code = "P" + System.nanoTime() % 100000 + i + System.currentTimeMillis() % 1000;
            mockMvc.perform(post("/api/v1/accounts")
                            .header("Authorization", authHeader())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createAccountJson(code, "Paginate " + i, "EXPENSE")))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.size").value(2));
        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void shouldSearchByNameOrCode() throws Exception {
        String unique = "Cash" + System.nanoTime() % 100000;
        String code1 = "S" + System.nanoTime() % 100000;
        String code2 = "S" + (System.nanoTime() % 100000 + 1);
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountJson(code1, unique + " Supplies", "ASSET")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountJson(code2, unique + " Web", "ASSET")))
                .andExpect(status().isCreated());
        String otherCode = "O" + System.nanoTime() % 100000;
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountJson(otherCode, "Other " + System.nanoTime(), "REVENUE")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .param("search", unique))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .param("search", code1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void shouldFilterByType() throws Exception {
        String codeA = "TA" + System.nanoTime() % 100000;
        String codeR = "TR" + System.nanoTime() % 100000;
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountJson(codeA, "TypeAsset " + System.nanoTime(), "ASSET")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountJson(codeR, "TypeRevenue " + System.nanoTime(), "REVENUE")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .param("type", "ASSET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].type").value("ASSET"));
    }

    @Test
    void shouldReturnTree() throws Exception {
        String parentCode = "5000T" + System.nanoTime() % 10000;
        String parentResp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountJson(parentCode, "Operating Expenses", "EXPENSE")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String parentId = objectMapper.readTree(parentResp).get("id").asText();

        String childCode1 = "5100T" + System.nanoTime() % 10000;
        String childCode2 = "5200T" + System.nanoTime() % 10000;
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountWithParentJson(childCode1, "Office Expenses", "EXPENSE", parentId)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountWithParentJson(childCode2, "Travel Expenses", "EXPENSE", parentId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/accounts/tree")
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.code=='" + parentCode + "')].children.length()").value(2));
    }

    @Test
    void shouldValidateMissingFields() throws Exception {
        String invalid = """
                {"code":"","name":""}
                """;
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.code").exists())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.type").exists());
    }

    @Test
    void shouldReturn409OnDuplicateCode() throws Exception {
        String code = "DUP" + System.nanoTime() % 100000;
        String json = createAccountJson(code, "Dup Account", "ASSET");
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void shouldAllowSameCodeInDifferentOrganizations() throws Exception {
        String code = "SHARED" + System.nanoTime() % 100000;
        String json = createAccountJson(code, "Shared Code", "ASSET");
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());

        String suffix = "shared-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Shared", "User",
                "shared.acc." + suffix + "@example.com",
                "SecurePass123",
                "SharedOrgAcc-" + suffix,
                "Legal Shared " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldValidateParentBelongsToSameOrganization() throws Exception {
        // create org B and account there
        String suffix = "parent-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Other", "Parent",
                "parent.other." + suffix + "@example.com",
                "SecurePass123",
                "ParentOtherOrg-" + suffix,
                "Legal Other " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        String otherAccountJson = createAccountJson("P100" + System.nanoTime() % 10000, "Other Parent", "ASSET");
        String otherAccResp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otherAccountJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherParentId = objectMapper.readTree(otherAccResp).get("id").asText();

        // try to use other org's account as parent in our org
        String json = createAccountWithParentJson("CROSS" + System.nanoTime() % 10000, "Cross Parent", "ASSET", otherParentId);
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("different organization")));
    }

    @Test
    void shouldRejectSelfParent() throws Exception {
        String code = "SELF" + System.nanoTime() % 10000;
        String createResp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountJson(code, "Self Parent", "ASSET")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResp).get("id").asText();
        String updateJson = """
                {"parentAccountId":"%s"}
                """.formatted(id);
        mockMvc.perform(put("/api/v1/accounts/{id}", id)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("own parent")));
    }

    @Test
    void shouldHandleCrossTenantRetrievalRejection() throws Exception {
        String code = "CROSSR" + System.nanoTime() % 10000;
        String createResp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountJson(code, "Cross Retrieve", "ASSET")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResp).get("id").asText();

        String suffix = "crossr-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Cross", "Retr",
                "cross.retr." + suffix + "@example.com",
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

        mockMvc.perform(get("/api/v1/accounts/{id}", id)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldHandleCrossTenantUpdateAndDeleteRejection() throws Exception {
        String code = "CROSSUD" + System.nanoTime() % 10000;
        String createResp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountJson(code, "Cross UD", "ASSET")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResp).get("id").asText();

        String suffix = "crossud-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Cross", "UD",
                "cross.ud." + suffix + "@example.com",
                "SecurePass123",
                "CrossOrgUD-" + suffix,
                "Legal Cross UD " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();

        mockMvc.perform(put("/api/v1/accounts/{id}", id)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hacked\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/accounts/{id}", id)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", "Bearer " + otherToken)
                        .param("search", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", "Bearer " + otherToken)
                        .param("type", "ASSET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/accounts/tree")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='" + code + "')]").doesNotExist());
    }

    @Test
    void shouldAllowViewerReadButNotModify() throws Exception {
        String suffix = "view-acc-" + System.nanoTime();
        String viewerJson = """
                {"firstName":"Viewer","lastName":"User","email":"viewer.acc.%s@example.com","password":"SecurePass123"}
                """.formatted(suffix);
        String viewerResp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(viewerJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String viewerUserId = objectMapper.readTree(viewerResp).get("id").asText();

        String addViewerJson = """
                {"userId":"%s","role":"VIEWER"}
                """.formatted(viewerUserId);
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", orgId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addViewerJson))
                .andExpect(status().isCreated());

        String loginJson = """
                {"email":"viewer.acc.%s@example.com","password":"SecurePass123"}
                """.formatted(suffix);
        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewerToken = objectMapper.readTree(loginResp).get("accessToken").asText();

        // viewer can read list
        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        // viewer can read tree
        mockMvc.perform(get("/api/v1/accounts/tree")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        // viewer cannot create
        String createJson = createAccountJson("VWR" + System.nanoTime() % 10000, "Viewer Create", "ASSET");
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isForbidden());

        // create an account as owner for further checks
        String ownerCreateJson = createAccountJson("VWRDEL" + System.nanoTime() % 10000, "Viewer Delete Target", "ASSET");
        String ownerCreateResp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ownerCreateJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String accId = objectMapper.readTree(ownerCreateResp).get("id").asText();

        // viewer cannot update
        mockMvc.perform(put("/api/v1/accounts/{id}", accId)
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ViewerUpdate\"}"))
                .andExpect(status().isForbidden());

        // viewer cannot delete
        mockMvc.perform(delete("/api/v1/accounts/{id}", accId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowEmployeeReadButNotModify() throws Exception {
        String suffix = "emp-acc-" + System.nanoTime();
        String empJson = """
                {"firstName":"Emp","lastName":"User","email":"emp.acc.%s@example.com","password":"SecurePass123"}
                """.formatted(suffix);
        String empResp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(empJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String empUserId = objectMapper.readTree(empResp).get("id").asText();

        String addEmpJson = """
                {"userId":"%s","role":"EMPLOYEE"}
                """.formatted(empUserId);
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", orgId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addEmpJson))
                .andExpect(status().isCreated());

        String loginJson = """
                {"email":"emp.acc.%s@example.com","password":"SecurePass123"}
                """.formatted(suffix);
        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String empToken = objectMapper.readTree(loginResp).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", "Bearer " + empToken))
                .andExpect(status().isOk());

        String createJson = createAccountJson("EMP" + System.nanoTime() % 10000, "Emp Create", "ASSET");
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + empToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldProtectSystemAccounts() throws Exception {
        String code = "SYS" + System.nanoTime() % 10000;
        String createResp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountJson(code, "System Account", "ASSET")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResp).get("id").asText();

        // Mark as system via repository
        Account acc = accountRepository.findById(UUID.fromString(id)).orElseThrow();
        acc.setSystemAccount(true);
        accountRepository.save(acc);

        mockMvc.perform(delete("/api/v1/accounts/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("system account")));

        String updateJson = """
                {"active":false}
                """;
        mockMvc.perform(put("/api/v1/accounts/{id}", id)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn401WhenMissingAuth() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountJson("NOAUTH", "No Auth", "ASSET")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldIsolateOrganizationOnListAndTree() throws Exception {
        String suffix = "iso-list-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Iso", "List",
                "iso.list." + suffix + "@example.com",
                "SecurePass123",
                "IsoListOrg-" + suffix,
                "Legal Iso List " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();

        String uniqueCode = "ISOL" + System.nanoTime() % 100000;
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountJson(uniqueCode, "Isolation Test", "ASSET")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", "Bearer " + otherToken)
                        .param("search", uniqueCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/accounts/tree")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='" + uniqueCode + "')]").doesNotExist());
    }
}
