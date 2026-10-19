package com.example.accounting.controller;

import com.example.accounting.dto.CreateOrganizationRequest;
import com.example.accounting.dto.CreateUserRequest;
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
class VendorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String ownerToken;
    private String ownerUserId;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = "ven-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Vendor", "Owner",
                "vendor.owner." + suffix + "@example.com",
                "SecurePass123",
                "VendorOrg-" + suffix,
                "Legal Vendor " + suffix,
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
    void shouldCreateVendor() throws Exception {
        String json = """
                {
                    "name": "Acme Vendor %s",
                    "email": "vendor%s@example.com",
                    "phone": "1234567890",
                    "address": "123 Street",
                    "taxIdentifier": "TAX123",
                    "paymentTerms": "NET30"
                }
                """.formatted(System.nanoTime(), System.nanoTime());

        mockMvc.perform(post("/api/v1/vendors")
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
    void shouldGetVendor() throws Exception {
        String json = """
                {"name": "GetVendor %s", "email": "get%s@example.com"}
                """.formatted(System.nanoTime(), System.nanoTime());

        String createResp = mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String vendorId = objectMapper.readTree(createResp).get("id").asText();

        mockMvc.perform(get("/api/v1/vendors/{id}", vendorId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(vendorId))
                .andExpect(jsonPath("$.organizationId").value(orgId));
    }

    @Test
    void shouldUpdateVendor() throws Exception {
        String createJson = """
                {"name": "UpdateVendor %s", "email": "upd%s@example.com"}
                """.formatted(System.nanoTime(), System.nanoTime());
        String createResp = mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String vendorId = objectMapper.readTree(createResp).get("id").asText();

        String updateJson = """
                {"name": "UpdatedName %s", "phone": "9999999999"}
                """.formatted(System.nanoTime());

        mockMvc.perform(put("/api/v1/vendors/{id}", vendorId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", org.hamcrest.Matchers.startsWith("UpdatedName")))
                .andExpect(jsonPath("$.phone").value("9999999999"));
    }

    @Test
    void shouldDeactivateVendor() throws Exception {
        String json = """
                {"name": "Deactivate %s"}
                """.formatted(System.nanoTime());
        String createResp = mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String vendorId = objectMapper.readTree(createResp).get("id").asText();

        mockMvc.perform(delete("/api/v1/vendors/{id}", vendorId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isNoContent());

        // verify deactivated: get should still return but active false
        mockMvc.perform(get("/api/v1/vendors/{id}", vendorId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // list should exclude inactive
        mockMvc.perform(get("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .param("search", "Deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void shouldListVendors() throws Exception {
        // ensure at least 2 vendors
        for (int i = 0; i < 2; i++) {
            String json = """
                    {"name": "ListVendor %s %s"}
                    """.formatted(System.nanoTime() + i, i);
            mockMvc.perform(post("/api/v1/vendors")
                            .header("Authorization", authHeader())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/vendors")
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber());
    }

    @Test
    void shouldPaginate() throws Exception {
        // create 5 vendors
        for (int i = 0; i < 5; i++) {
            String json = """
                    {"name": "Paginate %s %s"}
                    """.formatted(System.nanoTime() + i, i);
            mockMvc.perform(post("/api/v1/vendors")
                            .header("Authorization", authHeader())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.size").value(2));

        mockMvc.perform(get("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void shouldSearchByName() throws Exception {
        String unique = "Amazon" + System.nanoTime();
        String json1 = """
                {"name": "%s Supplies"}
                """.formatted(unique);
        String json2 = """
                {"name": "%s Web"}
                """.formatted(unique);
        String json3 = """
                {"name": "Google %s"}
                """.formatted(System.nanoTime());

        mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json1))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json2))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json3))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .param("search", unique))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void shouldValidateVendorName() throws Exception {
        String invalid = """
                {"name": "", "email": "not-email"}
                """;
        mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    @Test
    void shouldReturn404WhenVendorNotFound() throws Exception {
        String randomId = java.util.UUID.randomUUID().toString();
        mockMvc.perform(get("/api/v1/vendors/{id}", randomId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void shouldIsolateTenant() throws Exception {
        // create vendor in org A
        String json = """
                {"name": "TenantIsolation %s"}
                """.formatted(System.nanoTime());
        String createResp = mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String vendorId = objectMapper.readTree(createResp).get("id").asText();

        // create second tenant (different org/user)
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

        // other tenant should NOT see vendor (404)
        mockMvc.perform(get("/api/v1/vendors/{id}", vendorId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        // other tenant listing should not contain vendor
        mockMvc.perform(get("/api/v1/vendors")
                        .header("Authorization", "Bearer " + otherToken)
                        .param("search", "TenantIsolation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // other tenant update should be 404
        mockMvc.perform(put("/api/v1/vendors/{id}", vendorId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hacked\"}"))
                .andExpect(status().isNotFound());

        // other tenant delete should be 404
        mockMvc.perform(delete("/api/v1/vendors/{id}", vendorId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldEnforceAuthorizationViewerCannotCreate() throws Exception {
        // create viewer user in same org via membership
        String suffix = "view-" + System.nanoTime();
        // create viewer user via /users
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

        // owner adds viewer as VIEWER
        String addViewerJson = """
                {"userId":"%s","role":"VIEWER"}
                """.formatted(viewerUserId);
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", orgId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addViewerJson))
                .andExpect(status().isCreated());

        // login as viewer
        String loginJson = """
                {"email":"viewer.%s@example.com","password":"SecurePass123"}
                """.formatted(suffix);
        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewerToken = objectMapper.readTree(loginResp).get("accessToken").asText();

        // viewer can list (allowed)
        mockMvc.perform(get("/api/v1/vendors")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        // viewer cannot create
        String createJson = """
                {"name": "ViewerCreate %s"}
                """.formatted(System.nanoTime());
        mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isForbidden());

        // viewer cannot delete (need OWNER/ADMIN)
        // create a vendor as owner first to test delete
        String vendorJson = """
                {"name": "ViewerDeleteTest %s"}
                """.formatted(System.nanoTime());
        String vendorResp = mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vendorJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String vendorId = objectMapper.readTree(vendorResp).get("id").asText();

        mockMvc.perform(delete("/api/v1/vendors/{id}", vendorId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn409OnDuplicateVendorName() throws Exception {
        String name = "DupVendor " + System.nanoTime();
        String json = """
                {"name": "%s"}
                """.formatted(name);
        mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void shouldReturn401WhenMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/vendors"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/vendors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NoAuth\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAllowSameVendorNameInDifferentOrgs() throws Exception {
        String name = "SharedName " + System.nanoTime();
        String json = """
                {"name": "%s"}
                """.formatted(name);
        mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());

        // create second org/token
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

        mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());
    }
}
