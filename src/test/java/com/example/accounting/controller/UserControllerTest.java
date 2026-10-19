package com.example.accounting.controller;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = registerAndGetToken("admin-" + System.nanoTime());
    }

    private String registerAndGetToken(String suffix) throws Exception {
        RegisterRequest req = new RegisterRequest(
                "Admin", "User",
                "admin." + suffix + "@example.com",
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
        return objectMapper.readTree(resp).get("accessToken").asText();
    }

    private CreateUserRequest validRequest(String emailSuffix) {
        return new CreateUserRequest("John", "Doe", "john." + emailSuffix + "@example.com", "SecurePass123");
    }

    @Test
    void shouldCreateUser() throws Exception {
        CreateUserRequest req = validRequest("create" + System.nanoTime());

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value(req.email().toLowerCase()))
                .andExpect(jsonPath("$.firstName").value(req.firstName()))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void shouldNotExposePasswordHashOnCreate() throws Exception {
        CreateUserRequest req = validRequest("nohash" + System.nanoTime());

        String response = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(response).doesNotContain("passwordHash");
        org.assertj.core.api.Assertions.assertThat(response).doesNotContain("SecurePass123");
        org.assertj.core.api.Assertions.assertThat(response).doesNotContain("password");
    }

    @Test
    void shouldGetUserById() throws Exception {
        CreateUserRequest req = validRequest("get" + System.nanoTime());
        String createResponse = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(get("/api/v1/users/{id}", id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.email").value(req.email().toLowerCase()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void shouldReturn404WhenUserNotFound() throws Exception {
        String randomId = java.util.UUID.randomUUID().toString();
        mockMvc.perform(get("/api/v1/users/{id}", randomId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void shouldReturn409OnDuplicateEmail() throws Exception {
        String suffix = "dup" + System.nanoTime();
        CreateUserRequest req = validRequest(suffix);

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void shouldReturn409OnDuplicateEmailCaseInsensitive() throws Exception {
        String base = "case" + System.nanoTime();
        CreateUserRequest req1 = new CreateUserRequest("John", "Doe", "Case" + base + "@Example.COM", "SecurePass123");
        CreateUserRequest req2 = new CreateUserRequest("Jane", "Doe", "case" + base + "@example.com", "AnotherPass123");

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldValidateMissingFields() throws Exception {
        String invalidJson = """
                {
                    "firstName": "",
                    "lastName": "",
                    "email": "not-an-email",
                    "password": "short"
                }
                """;

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.firstName").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void shouldReturn400WhenInvalidUuid() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}", "not-a-uuid")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passwordHashNeverReturnedOnGet() throws Exception {
        CreateUserRequest req = validRequest("hashget" + System.nanoTime());
        String createResponse = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(createResponse).get("id").asText();

        String getResponse = mockMvc.perform(get("/api/v1/users/{id}", id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(getResponse).doesNotContain("passwordHash");
        org.assertj.core.api.Assertions.assertThat(getResponse).doesNotContain("password");
        org.assertj.core.api.Assertions.assertThat(getResponse).doesNotContain(req.password());
    }

    @Test
    void shouldReturn401WhenMissingToken() throws Exception {
        CreateUserRequest req = validRequest("notoken" + System.nanoTime());
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }
}
