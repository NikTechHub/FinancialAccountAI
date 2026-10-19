package com.example.accounting.controller;

import com.example.accounting.dto.LoginRequest;
import com.example.accounting.dto.RefreshRequest;
import com.example.accounting.dto.RegisterRequest;
import com.example.accounting.entity.MembershipRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private RegisterRequest validRegisterRequest(String suffix) {
        return new RegisterRequest(
                "Auth", "User",
                "auth." + suffix + "@example.com",
                "SecurePass123",
                "AuthOrg-" + suffix,
                "Legal Auth " + suffix,
                "PRIVATE_LIMITED",
                "India", "INR", "Asia/Kolkata"
        );
    }

    @Test
    void shouldRegisterSuccessfully() throws Exception {
        RegisterRequest req = validRegisterRequest("reg-" + System.nanoTime());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value(req.email().toLowerCase()))
                .andExpect(jsonPath("$.organization.name").value(req.organizationName()))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.user.password").doesNotExist());
    }

    @Test
    void shouldNotExposePasswordHashOnRegister() throws Exception {
        RegisterRequest req = validRegisterRequest("nohash-" + System.nanoTime());

        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(response).doesNotContain("passwordHash");
        org.assertj.core.api.Assertions.assertThat(response).doesNotContain(req.password());
        org.assertj.core.api.Assertions.assertThat(response.toLowerCase()).doesNotContain("password");
    }

    @Test
    void shouldLoginSuccessfully() throws Exception {
        RegisterRequest reg = validRegisterRequest("login-" + System.nanoTime());
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest(reg.email(), reg.password());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(reg.email().toLowerCase()));
    }

    @Test
    void shouldReturn401OnInvalidPassword() throws Exception {
        RegisterRequest reg = validRegisterRequest("badpass-" + System.nanoTime());
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());

        LoginRequest badLogin = new LoginRequest(reg.email(), "WrongPassword123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badLogin)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void shouldReturn409OnDuplicateEmailRegister() throws Exception {
        RegisterRequest req = validRegisterRequest("dup-" + System.nanoTime());
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // same email again
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void shouldReturn401WhenMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + java.util.UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/organizations/" + java.util.UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"A\",\"lastName\":\"B\",\"email\":\"a@b.com\",\"password\":\"SecurePass123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAllowWithValidToken() throws Exception {
        RegisterRequest reg = validRegisterRequest("validtok-" + System.nanoTime());
        String regResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(regResp).get("accessToken").asText();
        String userId = objectMapper.readTree(regResp).get("user").get("id").asText();

        mockMvc.perform(get("/api/v1/users/{id}", userId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId));
    }

    @Test
    void shouldEnforceOrganizationMembershipAuthorization() throws Exception {
        // user A registers with org A
        RegisterRequest reqA = validRegisterRequest("memauthA-" + System.nanoTime());
        String respA = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqA)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tokenA = objectMapper.readTree(respA).get("accessToken").asText();
        String orgIdA = objectMapper.readTree(respA).get("organization").get("id").asText();

        // user B registers with org B
        RegisterRequest reqB = validRegisterRequest("memauthB-" + System.nanoTime());
        String respB = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqB)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tokenB = objectMapper.readTree(respB).get("accessToken").asText();

        // A can access own org
        mockMvc.perform(get("/api/v1/organizations/{id}", orgIdA)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        // B cannot access A's org -> 403
        mockMvc.perform(get("/api/v1/organizations/{id}", orgIdA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldIsolateTenants() throws Exception {
        // Tenant isolation: two orgs, two users, each only sees own members
        RegisterRequest req1 = validRegisterRequest("tenant1-" + System.nanoTime());
        String resp1 = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token1 = objectMapper.readTree(resp1).get("accessToken").asText();
        String org1Id = objectMapper.readTree(resp1).get("organization").get("id").asText();

        RegisterRequest req2 = validRegisterRequest("tenant2-" + System.nanoTime());
        String resp2 = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token2 = objectMapper.readTree(resp2).get("accessToken").asText();
        String org2Id = objectMapper.readTree(resp2).get("organization").get("id").asText();

        // user1 list members of org1 -> ok, contains 1 (owner)
        mockMvc.perform(get("/api/v1/organizations/{id}/members", org1Id)
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // user1 tries to list members of org2 -> should be forbidden (no membership)
        mockMvc.perform(get("/api/v1/organizations/{id}/members", org2Id)
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isForbidden());

        // user2 tries to list org1 -> forbidden
        mockMvc.perform(get("/api/v1/organizations/{id}/members", org1Id)
                        .header("Authorization", "Bearer " + token2))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldEnforceRoleRestrictions() throws Exception {
        // OWNER can add member, VIEWER cannot
        RegisterRequest ownerReq = validRegisterRequest("roleowner-" + System.nanoTime());
        String ownerResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ownerReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String ownerToken = objectMapper.readTree(ownerResp).get("accessToken").asText();
        String ownerOrgId = objectMapper.readTree(ownerResp).get("organization").get("id").asText();

        // create viewer user via /users (owner adds them as VIEWER)
        String viewerEmail = "viewer.role" + System.nanoTime() + "@example.com";
        String viewerUserJson = """
                {"firstName":"Viewer","lastName":"User","email":"%s","password":"SecurePass123"}
                """.formatted(viewerEmail);
        String viewerCreateResp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(viewerUserJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String viewerUserId = objectMapper.readTree(viewerCreateResp).get("id").asText();

        // owner adds viewer as VIEWER to org
        String addViewerJson = """
                {"userId":"%s","role":"VIEWER"}
                """.formatted(viewerUserId);
        mockMvc.perform(post("/api/v1/organizations/{orgId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addViewerJson))
                .andExpect(status().isCreated());

        // login as viewer to get token
        String loginViewerJson = """
                {"email":"%s","password":"SecurePass123"}
                """.formatted(viewerEmail);
        String viewerLoginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginViewerJson))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewerToken = objectMapper.readTree(viewerLoginResp).get("accessToken").asText();

        // viewer tries to add another member -> should be 403 (only OWNER/ADMIN allowed)
        String anotherUserEmail = "another." + System.nanoTime() + "@example.com";
        String anotherUserJson = """
                {"firstName":"Another","lastName":"User","email":"%s","password":"SecurePass123"}
                """.formatted(anotherUserEmail);
        String anotherResp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(anotherUserJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String anotherUserId = objectMapper.readTree(anotherResp).get("id").asText();

        String addAttemptJson = """
                {"userId":"%s","role":"EMPLOYEE"}
                """.formatted(anotherUserId);

        mockMvc.perform(post("/api/v1/organizations/{orgId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addAttemptJson))
                .andExpect(status().isForbidden());

        // viewer should still be able to list members (requires any membership)
        mockMvc.perform(get("/api/v1/organizations/{orgId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        // owner should succeed adding with ADMIN role
        // create yet another user
        String adminCandidateEmail = "admin.cand" + System.nanoTime() + "@example.com";
        String adminCandJson = """
                {"firstName":"Admin","lastName":"Cand","email":"%s","password":"SecurePass123"}
                """.formatted(adminCandidateEmail);
        String adminCandResp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adminCandJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String adminCandId = objectMapper.readTree(adminCandResp).get("id").asText();

        String addAdminJson = """
                {"userId":"%s","role":"ADMIN"}
                """.formatted(adminCandId);
        mockMvc.perform(post("/api/v1/organizations/{orgId}/members", ownerOrgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addAdminJson))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldHandleRefreshTokenFlow() throws Exception {
        RegisterRequest reg = validRegisterRequest("refresh-" + System.nanoTime());
        String regResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String refreshToken = objectMapper.readTree(regResp).get("refreshToken").asText();
        String oldAccessToken = objectMapper.readTree(regResp).get("accessToken").asText();

        // refresh with valid token -> new tokens
        String refreshJson = """
                {"refreshToken":"%s"}
                """.formatted(refreshToken);
        String refreshResp = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String newAccessToken = objectMapper.readTree(refreshResp).get("accessToken").asText();
        String newRefreshToken = objectMapper.readTree(refreshResp).get("refreshToken").asText();

        org.assertj.core.api.Assertions.assertThat(newAccessToken).isNotEqualTo(oldAccessToken);
        org.assertj.core.api.Assertions.assertThat(newRefreshToken).isNotEqualTo(refreshToken);

        // old refresh token should now be revoked -> 401
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson))
                .andExpect(status().isUnauthorized());

        // new refresh should work again (rotation)
        String secondRefreshJson = """
                {"refreshToken":"%s"}
                """.formatted(newRefreshToken);
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondRefreshJson))
                .andExpect(status().isOk());

        // new access token should be valid for protected endpoint
        String userId = objectMapper.readTree(regResp).get("user").get("id").asText();
        mockMvc.perform(get("/api/v1/users/{id}", userId)
                        .header("Authorization", "Bearer " + newAccessToken))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReturn401OnInvalidRefreshToken() throws Exception {
        String badRefreshJson = """
                {"refreshToken":"invalid.token.here"}
                """;
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badRefreshJson))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn401WhenAccessTokenExpiredOrInvalid() throws Exception {
        // tampered token
        RegisterRequest reg = validRegisterRequest("badtoken-" + System.nanoTime());
        String regResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String validToken = objectMapper.readTree(regResp).get("accessToken").asText();
        String tampered = validToken + "tamper";

        mockMvc.perform(get("/api/v1/users/" + objectMapper.readTree(regResp).get("user").get("id").asText())
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldNotExposeSecretsOnAuth() throws Exception {
        RegisterRequest req = validRegisterRequest("clean-" + System.nanoTime());
        String resp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(resp).doesNotContain("passwordHash");
        org.assertj.core.api.Assertions.assertThat(resp).doesNotContain(req.password());
        // ensure JWT secret not leaked (check for actual secret values / env var names)
        org.assertj.core.api.Assertions.assertThat(resp).doesNotContain("JWT_SECRET");
        org.assertj.core.api.Assertions.assertThat(resp).doesNotContain("JWT_ACCESS_TOKEN_EXPIRATION");
        org.assertj.core.api.Assertions.assertThat(resp).doesNotContain("JWT_REFRESH_TOKEN_EXPIRATION");
    }
}
