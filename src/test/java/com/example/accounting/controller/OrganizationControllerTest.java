package com.example.accounting.controller;

import com.example.accounting.dto.CreateOrganizationRequest;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrganizationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String authToken;
    private String authOrgId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = "orgtest-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Org", "Tester",
                "org." + suffix + "@example.com",
                "SecurePass123",
                "Org-" + suffix,
                "Legal Org " + suffix,
                "PRIVATE_LIMITED",
                "India", "INR", "Asia/Kolkata"
        );
        String resp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        authToken = objectMapper.readTree(resp).get("accessToken").asText();
        authOrgId = objectMapper.readTree(resp).get("organization").get("id").asText();
    }

    private CreateOrganizationRequest validRequest(String suffix) {
        return new CreateOrganizationRequest(
                "Org-" + suffix,
                "Legal Org " + suffix,
                "PRIVATE_LIMITED",
                "India",
                "INR",
                "Asia/Kolkata"
        );
    }

    @Test
    void shouldCreateOrganization() throws Exception {
        CreateOrganizationRequest request = validRequest("Create-" + System.nanoTime());

        mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value(request.name()));
    }

    @Test
    void shouldGetOrganizationById() throws Exception {
        // authOrgId is already owned by auth user
        mockMvc.perform(get("/api/v1/organizations/{id}", authOrgId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(authOrgId));
    }

    @Test
    void shouldReturn404WhenOrganizationNotFound() throws Exception {
        String randomId = java.util.UUID.randomUUID().toString();

        mockMvc.perform(get("/api/v1/organizations/{id}", randomId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void shouldReturn403WhenNotMember() throws Exception {
        // create second user/org, try to access first org's data with second user's token -> should be 403 or 404? We enforce 403 via AccessDenied
        String suffix = "isol-" + System.nanoTime();
        RegisterRequest req2 = new RegisterRequest(
                "Other", "User",
                "other." + suffix + "@example.com",
                "SecurePass123",
                "OtherOrg-" + suffix,
                "Legal Other " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String resp2 = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(resp2).get("accessToken").asText();

        // other user tries to fetch first user's org -> should be forbidden (no membership)
        mockMvc.perform(get("/api/v1/organizations/{id}", authOrgId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn400WhenValidationFails() throws Exception {
        String invalidJson = """
                {
                    "name": "",
                    "legalName": "",
                    "businessType": "",
                    "country": "",
                    "currency": "TOOLONG",
                    "timezone": ""
                }
                """;

        mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.name").exists());
    }

    @Test
    void shouldReturn409WhenDuplicateName() throws Exception {
        String suffix = "Dup-" + System.nanoTime();
        CreateOrganizationRequest request = validRequest(suffix);

        mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void shouldReturn401WhenMissingToken() throws Exception {
        CreateOrganizationRequest request = validRequest("noauth" + System.nanoTime());
        mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn400WhenInvalidUuid() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{id}", "not-a-uuid")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn401WhenInvalidToken() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{id}", authOrgId)
                        .header("Authorization", "Bearer invalid.token.here"))
                .andExpect(status().isUnauthorized());
    }
}
