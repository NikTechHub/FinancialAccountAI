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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String ownerToken;
    private String ownerUserId;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = "cust-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Customer", "Owner",
                "customer.owner." + suffix + "@example.com",
                "SecurePass123",
                "CustOrg-" + suffix,
                "Legal Cust " + suffix,
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

    @Test
    void shouldCreateCustomer() throws Exception {
        String json = """
                {
                    "name": "Acme Customer %s",
                    "email": "customer%s@example.com",
                    "phone": "1234567890",
                    "address": "123 Street",
                    "taxIdentifier": "TAX123",
                    "paymentTerms": "NET30"
                }
                """.formatted(System.nanoTime(), System.nanoTime());

        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.organizationId").value(orgId))
                .andExpect(jsonPath("$.name").exists())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void shouldGetCustomer() throws Exception {
        String json = """
                {"name": "GetCustomer %s", "email": "get%s@example.com"}
                """.formatted(System.nanoTime(), System.nanoTime());

        String createResp = mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String customerId = objectMapper.readTree(createResp).get("id").asText();

        mockMvc.perform(get("/api/v1/customers/{id}", customerId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customerId))
                .andExpect(jsonPath("$.organizationId").value(orgId));
    }

    @Test
    void shouldUpdateCustomer() throws Exception {
        String createJson = """
                {"name": "UpdateCustomer %s", "email": "upd%s@example.com"}
                """.formatted(System.nanoTime(), System.nanoTime());
        String createResp = mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String customerId = objectMapper.readTree(createResp).get("id").asText();

        String updateJson = """
                {"name": "UpdatedName %s", "phone": "9999999999"}
                """.formatted(System.nanoTime());

        mockMvc.perform(put("/api/v1/customers/{id}", customerId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", org.hamcrest.Matchers.startsWith("UpdatedName")))
                .andExpect(jsonPath("$.phone").value("9999999999"));
    }

    @Test
    void shouldDeactivateCustomer() throws Exception {
        String json = """
                {"name": "Deactivate %s"}
                """.formatted(System.nanoTime());
        String createResp = mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String customerId = objectMapper.readTree(createResp).get("id").asText();

        mockMvc.perform(delete("/api/v1/customers/{id}", customerId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/customers/{id}", customerId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .param("search", "Deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void shouldListCustomers() throws Exception {
        for (int i = 0; i < 2; i++) {
            String json = """
                    {"name": "ListCustomer %s %s"}
                    """.formatted(System.nanoTime() + i, i);
            mockMvc.perform(post("/api/v1/customers")
                            .header("Authorization", authHeader())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/customers")
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber());
    }

    @Test
    void shouldPaginate() throws Exception {
        for (int i = 0; i < 5; i++) {
            String json = """
                    {"name": "Paginate %s %s"}
                    """.formatted(System.nanoTime() + i, i);
            mockMvc.perform(post("/api/v1/customers")
                            .header("Authorization", authHeader())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.size").value(2));

        mockMvc.perform(get("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void shouldSearchByName() throws Exception {
        String unique = "Acme" + System.nanoTime();
        String json1 = """
                {"name": "%s Supplies"}
                """.formatted(unique);
        String json2 = """
                {"name": "%s Web"}
                """.formatted(unique);
        String json3 = """
                {"name": "Globex %s"}
                """.formatted(System.nanoTime());

        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json1))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json2))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json3))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .param("search", unique))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void shouldValidateCustomerName() throws Exception {
        String invalid = """
                {"name": "", "email": "not-email"}
                """;
        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    @Test
    void shouldReturn404WhenCustomerNotFound() throws Exception {
        String randomId = java.util.UUID.randomUUID().toString();
        mockMvc.perform(get("/api/v1/customers/{id}", randomId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void shouldIsolateTenant() throws Exception {
        String json = """
                {"name": "TenantIsolation %s"}
                """.formatted(System.nanoTime());
        String createResp = mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String customerId = objectMapper.readTree(createResp).get("id").asText();

        String suffix = "iso-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Other", "Tenant",
                "other.tenant." + suffix + "@example.com",
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
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/customers/{id}", customerId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/customers")
                        .header("Authorization", "Bearer " + otherToken)
                        .param("search", "TenantIsolation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(put("/api/v1/customers/{id}", customerId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hacked\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/customers/{id}", customerId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        // Verify organization B cannot search/list A's customers
        // Explicitly test that organization A cannot retrieve B's customer
        // Create customer in other org
        String otherJson = """
                {"name": "OtherOrgCustomer %s"}
                """.formatted(System.nanoTime());
        String otherCustResp = mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otherJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherCustId = objectMapper.readTree(otherCustResp).get("id").asText();

        mockMvc.perform(get("/api/v1/customers/{id}", otherCustId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isNotFound());

        // search isolation
        String uniqueSearch = "UniqueTenantSearch" + System.nanoTime();
        String custInA = """
                {"name": "%s"}
                """.formatted(uniqueSearch);
        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(custInA))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/customers")
                        .header("Authorization", "Bearer " + otherToken)
                        .param("search", uniqueSearch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void shouldEnforceAuthorizationViewerCannotCreate() throws Exception {
        String suffix = "view-" + System.nanoTime();
        String viewerJson = """
                {"firstName":"Viewer","lastName":"User","email":"viewer.%s@example.com","password":"SecurePass123"}
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
                {"email":"viewer.%s@example.com","password":"SecurePass123"}
                """.formatted(suffix);
        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewerToken = objectMapper.readTree(loginResp).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/customers")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        String createJson = """
                {"name": "ViewerCreate %s"}
                """.formatted(System.nanoTime());
        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isForbidden());

        String customerJson = """
                {"name": "ViewerDeleteTest %s"}
                """.formatted(System.nanoTime());
        String custResp = mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String custId = objectMapper.readTree(custResp).get("id").asText();

        mockMvc.perform(put("/api/v1/customers/{id}", custId)
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ViewerUpdate\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/customers/{id}", custId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn409OnDuplicateCustomerName() throws Exception {
        String name = "DupCustomer " + System.nanoTime();
        String json = """
                {"name": "%s"}
                """.formatted(name);
        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void shouldReturn401WhenMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/customers"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NoAuth\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAllowSameCustomerNameInDifferentOrgs() throws Exception {
        String name = "SharedName " + System.nanoTime();
        String json = """
                {"name": "%s"}
                """.formatted(name);
        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());

        String suffix = "shared-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Shared", "User",
                "shared." + suffix + "@example.com",
                "SecurePass123",
                "SharedOrg-" + suffix,
                "Legal Shared " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();

        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldNotTrustOrganizationIdFromBody() throws Exception {
        // Try to send organizationId in body (should be ignored, vendor still created in auth user's org)
        String jsonWithOrg = """
                {
                    "name": "NoOrgTrust %s",
                    "organizationId": "%s"
                }
                """.formatted(System.nanoTime(), java.util.UUID.randomUUID().toString());

        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonWithOrg))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizationId").value(orgId));
    }
}
