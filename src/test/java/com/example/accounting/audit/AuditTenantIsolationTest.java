package com.example.accounting.audit;

import com.example.accounting.dto.RegisterRequest;
import com.example.accounting.entity.MembershipRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant isolation and role-gating for the read-only audit API.
 *
 * <p>Covers spec section 33: cross-org invisibility, safe 404 for guessed IDs,
 * role matrix, and filter scoping.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditTenantIsolationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private record OrgCtx(String token, String userId, String orgId, String email) {}

    private OrgCtx register(String suffix) throws Exception {
        RegisterRequest req = new RegisterRequest(
                "Audit", "Owner",
                "audit.iso." + suffix + "@example.com",
                "SecurePass123",
                "AuditIsoOrg-" + suffix,
                "Legal " + suffix,
                "PRIVATE_LIMITED", "India", "INR", "Asia/Kolkata");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new OrgCtx(json.get("accessToken").asText(),
                json.get("user").get("id").asText(),
                json.get("organization").get("id").asText(),
                req.email().toLowerCase());
    }

    private String addMemberWithRole(OrgCtx owner, MembershipRole role) throws Exception {
        // Create an org-less user (no readable orgs of their own), add them with
        // the target role, then log in. This isolates the role under test.
        String email = "auditm." + role.name().toLowerCase() + "." + System.nanoTime() + "@example.com";
        String createJson = """
                {"firstName":"Audit","lastName":"Member","email":"%s","password":"SecurePass123"}
                """.formatted(email);
        MvcResult created = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andReturn();
        String userId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        String membershipJson = """
                {"userId":"%s","role":"%s"}
                """.formatted(userId, role.name());
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", owner.orgId())
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(membershipJson))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"SecurePass123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String createVendor(String token, String name) throws Exception {
        String json = """
                {"name":"%s"}
                """.formatted(name);
        MvcResult result = mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void organizationACannotSeeOrganizationBAuditLogs() throws Exception {
        OrgCtx a = register("a-" + System.nanoTime());
        OrgCtx b = register("b-" + System.nanoTime());
        createVendor(a.token(), "VendorA-" + System.nanoTime());
        createVendor(b.token(), "VendorB-" + System.nanoTime());

        // A lists audits: every returned row must belong to A's org.
        MvcResult result = mockMvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(page.get("content").size()).isGreaterThan(0);
        for (JsonNode row : page.get("content")) {
            assertThat(row.get("organizationId").asText()).isEqualTo(a.orgId());
        }
        // B's org must never appear in A's listing.
        for (JsonNode row : page.get("content")) {
            assertThat(row.get("organizationId").asText()).isNotEqualTo(b.orgId());
        }
    }

    @Test
    void guessedAuditIdFromOtherOrgReturns404() throws Exception {
        OrgCtx a = register("g1-" + System.nanoTime());
        OrgCtx b = register("g2-" + System.nanoTime());
        String vendorId = createVendor(b.token(), "VendorG-" + System.nanoTime());

        // Find one of B's audit rows directly.
        MvcResult bList = mockMvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", "Bearer " + b.token())
                        .param("resourceId", vendorId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode bPage = objectMapper.readTree(bList.getResponse().getContentAsString());
        assertThat(bPage.get("content").size()).isGreaterThan(0);
        String auditId = bPage.get("content").get(0).get("id").asText();

        // A guesses B's audit ID -> safe 404, not 403 with existence leak.
        mockMvc.perform(get("/api/v1/audit-logs/{id}", auditId)
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void employeeAndViewerCannotAccessAuditApi() throws Exception {
        OrgCtx owner = register("ev-" + System.nanoTime());
        String employeeToken = addMemberWithRole(owner, MembershipRole.EMPLOYEE);
        String viewerToken = addMemberWithRole(owner, MembershipRole.VIEWER);

        mockMvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerAdminAccountantCanAccessAuditApi() throws Exception {
        OrgCtx owner = register("oaa-" + System.nanoTime());
        String adminToken = addMemberWithRole(owner, MembershipRole.ADMIN);
        String accountantToken = addMemberWithRole(owner, MembershipRole.ACCOUNTANT);

        mockMvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", "Bearer " + accountantToken))
                .andExpect(status().isOk());
    }

    @Test
    void filtersCannotEscapeTenantScope() throws Exception {
        OrgCtx a = register("f1-" + System.nanoTime());
        OrgCtx b = register("f2-" + System.nanoTime());
        String vendorB = createVendor(b.token(), "VendorF-" + System.nanoTime());

        // A filters by B's resourceId -> empty, not B's rows.
        MvcResult result = mockMvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", "Bearer " + a.token())
                        .param("resourceId", vendorB))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(page.get("totalElements").asLong()).isEqualTo(0);

        // A filters by B's actorUserId -> rows returned (if any) must still be A's org.
        MvcResult result2 = mockMvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", "Bearer " + a.token())
                        .param("actorUserId", b.userId()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page2 = objectMapper.readTree(result2.getResponse().getContentAsString());
        for (JsonNode row : page2.get("content")) {
            assertThat(row.get("organizationId").asText()).isEqualTo(a.orgId());
        }
    }

    @Test
    void unauthenticatedAuditAccessIs401() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/audit-logs/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidUuidAndDateRangeAre400() throws Exception {
        OrgCtx owner = register("bad-" + System.nanoTime());
        mockMvc.perform(get("/api/v1/audit-logs/{id}", "not-a-uuid")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", "Bearer " + owner.token())
                        .param("fromDate", "2026-12-31")
                        .param("toDate", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }
}
