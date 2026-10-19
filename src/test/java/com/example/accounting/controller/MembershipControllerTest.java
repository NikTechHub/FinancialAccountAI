package com.example.accounting.controller;

import com.example.accounting.dto.CreateMembershipRequest;
import com.example.accounting.dto.RegisterRequest;
import com.example.accounting.entity.MembershipRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MembershipControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String ownerToken;
    private String ownerOrgId;
    private String ownerUserId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = "mem-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Mem", "Owner",
                "memowner." + suffix + "@example.com",
                "SecurePass123",
                "MemOrg-" + suffix,
                "Legal Mem " + suffix,
                "LLP", "India", "INR", "Asia/Kolkata"
        );
        String resp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        ownerToken = objectMapper.readTree(resp).get("accessToken").asText();
        ownerOrgId = objectMapper.readTree(resp).get("organization").get("id").asText();
        ownerUserId = objectMapper.readTree(resp).get("user").get("id").asText();
    }

    private String createAnotherUserId() throws Exception {
        String suffix = "user-" + System.nanoTime() + Math.random();
        // create via /api/v1/users with owner token (protected)
        String json = """
                {
                    "firstName": "Another",
                    "lastName": "User",
                    "email": "another.%s@example.com",
                    "password": "SecurePass123"
                }
                """.formatted(suffix);
        String resp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("id").asText();
    }

    private String createViewerUserAndToken() throws Exception {
        String suffix = "viewer-" + System.nanoTime();
        RegisterRequest viewerReq = new RegisterRequest(
                "Viewer", "User",
                "viewer." + suffix + "@example.com",
                "SecurePass123",
                "ViewerOrg-" + suffix,
                "Legal Viewer " + suffix,
                "LLP", "India", "INR", "Asia/Kolkata"
        );
        String resp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(viewerReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String viewerToken = objectMapper.readTree(resp).get("accessToken").asText();
        String viewerUserId = objectMapper.readTree(resp).get("user").get("id").asText();
        String viewerOrgId = objectMapper.readTree(resp).get("organization").get("id").asText();

        // add viewer user as VIEWER to ownerOrg (owner adds them)
        CreateMembershipRequest addViewer = new CreateMembershipRequest(java.util.UUID.fromString(viewerUserId), MembershipRole.VIEWER);
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addViewer)))
                .andExpect(status().isCreated());

        return viewerToken;
    }

    @Test
    void shouldCreateMembership() throws Exception {
        String userId = createAnotherUserId();
        CreateMembershipRequest req = new CreateMembershipRequest(java.util.UUID.fromString(userId), MembershipRole.OWNER);

        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.organizationId").value(ownerOrgId))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void shouldPreventDuplicateMembership() throws Exception {
        String userId = createAnotherUserId();
        CreateMembershipRequest req = new CreateMembershipRequest(java.util.UUID.fromString(userId), MembershipRole.ADMIN);

        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void shouldAllowUserInMultipleOrganizations() throws Exception {
        // owner is already in ownerOrg, create second org via register
        String suffix = "multi-" + System.nanoTime();
        RegisterRequest secondOrgReq = new RegisterRequest(
                "Multi", "User2",
                "multi2." + suffix + "@example.com",
                "SecurePass123",
                "MultiOrg2-" + suffix,
                "Legal Multi2 " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String resp2 = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondOrgReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String secondOrgId = objectMapper.readTree(resp2).get("organization").get("id").asText();
        String secondUserId = objectMapper.readTree(resp2).get("user").get("id").asText();

        // add owner user to second org
        CreateMembershipRequest reqToSecond = new CreateMembershipRequest(java.util.UUID.fromString(ownerUserId), MembershipRole.EMPLOYEE);
        String secondToken = objectMapper.readTree(resp2).get("accessToken").asText();
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", secondOrgId)
                        .header("Authorization", "Bearer " + secondToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqToSecond)))
                .andExpect(status().isCreated());

        // verify owner has membership in both orgs via listing
        mockMvc.perform(get("/api/v1/organizations/{organizationId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)); // owner alone initially + maybe more

        mockMvc.perform(get("/api/v1/organizations/{organizationId}/members", secondOrgId)
                        .header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isOk());
    }

    @Test
    void shouldListOrganizationMembers() throws Exception {
        String userId1 = createAnotherUserId();
        String userId2 = createAnotherUserId();

        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateMembershipRequest(java.util.UUID.fromString(userId1), MembershipRole.ACCOUNTANT))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateMembershipRequest(java.util.UUID.fromString(userId2), MembershipRole.VIEWER))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/organizations/{organizationId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3)) // owner + 2
                .andExpect(jsonPath("$[0].role").exists())
                .andExpect(jsonPath("$[0].userEmail").exists());
    }

    @Test
    void shouldReturn404WhenOrganizationNotFoundOnCreateMembership() throws Exception {
        String randomOrgId = java.util.UUID.randomUUID().toString();
        String userId = createAnotherUserId();

        CreateMembershipRequest req = new CreateMembershipRequest(java.util.UUID.fromString(userId), MembershipRole.OWNER);

        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", randomOrgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void shouldReturn404WhenUserNotFoundOnCreateMembership() throws Exception {
        String randomUserId = java.util.UUID.randomUUID().toString();

        CreateMembershipRequest req = new CreateMembershipRequest(java.util.UUID.fromString(randomUserId), MembershipRole.OWNER);

        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void shouldReturn404WhenListingMembersOfNonExistingOrganization() throws Exception {
        String randomOrgId = java.util.UUID.randomUUID().toString();
        mockMvc.perform(get("/api/v1/organizations/{organizationId}/members", randomOrgId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldValidateMissingRole() throws Exception {
        String json = """
                {
                    "userId": "%s"
                }
                """.formatted(java.util.UUID.randomUUID().toString());

        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.role").exists());
    }

    @Test
    void shouldReturn400OnInvalidRole() throws Exception {
        String userId = createAnotherUserId();
        String json = """
                {
                    "userId": "%s",
                    "role": "INVALID_ROLE"
                }
                """.formatted(userId);

        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn403WhenViewerTriesToAddMember() throws Exception {
        String viewerToken = createViewerUserAndToken();
        String newUserId = createAnotherUserId();

        CreateMembershipRequest req = new CreateMembershipRequest(java.util.UUID.fromString(newUserId), MembershipRole.EMPLOYEE);

        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn401WhenMissingToken() throws Exception {
        String userId = createAnotherUserId();
        CreateMembershipRequest req = new CreateMembershipRequest(java.util.UUID.fromString(userId), MembershipRole.OWNER);

        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", ownerOrgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordHashNeverReturnedInMembershipListing() throws Exception {
        String userId = createAnotherUserId();
        String membershipJson = objectMapper.writeValueAsString(new CreateMembershipRequest(java.util.UUID.fromString(userId), MembershipRole.OWNER));
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(membershipJson))
                .andExpect(status().isCreated());

        String listResponse = mockMvc.perform(get("/api/v1/organizations/{organizationId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(listResponse).doesNotContain("passwordHash");
        org.assertj.core.api.Assertions.assertThat(listResponse).doesNotContain("password");
    }
}
