package com.example.accounting.cache;

import com.example.accounting.dto.RegisterRequest;
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
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tenant-isolation verification for the Redis/in-memory cache layer.
 * Two organizations each hold identically named accounts, vendors and
 * customers; every assertion proves cached data never crosses org boundaries.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CacheTenantIsolationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantCacheService tenantCache;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        tenantCache.clearAll();
        tokenA = register("isoA");
        tokenB = register("isoB");
    }

    private String register(String prefix) throws Exception {
        String suffix = prefix + "-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Cache", "Owner",
                "cache." + suffix + "@example.com",
                "SecurePass123",
                "CacheOrg-" + suffix,
                "Legal " + suffix,
                "PRIVATE_LIMITED",
                "India", "INR", "Asia/Kolkata"
        );
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String createAccount(String token, String code, String name) throws Exception {
        String json = """
                {"code":"%s","name":"%s","type":"ASSET"}
                """.formatted(code, name);
        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createVendor(String token, String name) throws Exception {
        String json = """
                {"name":"%s"}
                """.formatted(name);
        MvcResult result = mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createCustomer(String token, String name) throws Exception {
        String json = """
                {"name":"%s"}
                """.formatted(name);
        MvcResult result = mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String getTree(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/accounts/tree")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    @Test
    void accountTreeIsIsolatedAcrossOrgs() throws Exception {
        String shared = "SharedCash-" + System.nanoTime();
        createAccount(tokenA, "9001", shared);
        createAccount(tokenB, "9001", shared);

        // Populate A's cache first, then B must still see only its own data.
        String treeA = getTree(tokenA);
        String treeB = getTree(tokenB);

        assertThat(treeA).contains(shared);
        assertThat(treeB).contains(shared);
        // Each tree contains exactly one root with the shared name.
        assertThat(countOccurrences(treeA, shared)).isEqualTo(1);
        assertThat(countOccurrences(treeB, shared)).isEqualTo(1);

        // IDs must differ across orgs (no shared cached object).
        String idA = objectMapper.readTree(treeA).get(0).get("id").asText();
        String idB = objectMapper.readTree(treeB).get(0).get("id").asText();
        assertThat(idA).isNotEqualTo(idB);
    }

    @Test
    void sameSearchAcrossOrgsReturnsOnlyOwnAccounts() throws Exception {
        String shared = "SearchTarget-" + System.nanoTime();
        createAccount(tokenA, "9101", shared);
        createAccount(tokenB, "9101", shared);

        MvcResult resA = mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", bearer(tokenA))
                        .param("search", shared))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult resB = mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", bearer(tokenB))
                        .param("search", shared))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode contentA = objectMapper.readTree(resA.getResponse().getContentAsString()).get("content");
        JsonNode contentB = objectMapper.readTree(resB.getResponse().getContentAsString()).get("content");
        assertThat(contentA.size()).isEqualTo(1);
        assertThat(contentB.size()).isEqualTo(1);
        assertThat(contentA.get(0).get("id").asText())
                .isNotEqualTo(contentB.get(0).get("id").asText());
        assertThat(contentA.get(0).get("organizationId").asText())
                .isNotEqualTo(contentB.get(0).get("organizationId").asText());
    }

    @Test
    void sameVendorAndCustomerNamesAreIsolated() throws Exception {
        String shared = "SharedCo-" + System.nanoTime();
        createVendor(tokenA, shared);
        createVendor(tokenB, shared);
        createCustomer(tokenA, shared);
        createCustomer(tokenB, shared);

        // Warm caches for A, then verify B sees only its own records.
        mockMvc.perform(get("/api/v1/vendors")
                        .header("Authorization", bearer(tokenA))
                        .param("search", shared))
                .andExpect(status().isOk());
        MvcResult resB = mockMvc.perform(get("/api/v1/vendors")
                        .header("Authorization", bearer(tokenB))
                        .param("search", shared))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode vendorsB = objectMapper.readTree(resB.getResponse().getContentAsString()).get("content");
        assertThat(vendorsB.size()).isEqualTo(1);
        assertThat(vendorsB.get(0).get("name").asText()).isEqualTo(shared);

        MvcResult resCB = mockMvc.perform(get("/api/v1/customers")
                        .header("Authorization", bearer(tokenB))
                        .param("search", shared))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode customersB = objectMapper.readTree(resCB.getResponse().getContentAsString()).get("content");
        assertThat(customersB.size()).isEqualTo(1);

        // Vendor IDs across orgs differ.
        MvcResult resA = mockMvc.perform(get("/api/v1/vendors")
                        .header("Authorization", bearer(tokenA))
                        .param("search", shared))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode vendorsA = objectMapper.readTree(resA.getResponse().getContentAsString()).get("content");
        assertThat(vendorsA.get(0).get("id").asText())
                .isNotEqualTo(vendorsB.get(0).get("id").asText());
    }

    @Test
    void mutationInOrgAInvalidatesOnlyOrgACache() throws Exception {
        String idA = createAccount(tokenA, "9201", "BeforeRename-" + System.nanoTime());
        createAccount(tokenB, "9201", "Stable-" + System.nanoTime());

        String treeA1 = getTree(tokenA);
        String treeB1 = getTree(tokenB);
        assertThat(treeA1).contains("BeforeRename-");

        // Rename A's account: only A's caches may be invalidated.
        String renamed = "AfterRename-" + System.nanoTime();
        mockMvc.perform(put("/api/v1/accounts/{id}", idA)
                        .header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(renamed)))
                .andExpect(status().isOk());

        String treeA2 = getTree(tokenA);
        String treeB2 = getTree(tokenB);
        assertThat(treeA2).contains(renamed);
        assertThat(treeA2).doesNotContain("BeforeRename-");
        // B's cached data is untouched and still valid.
        assertThat(treeB2).isEqualTo(treeB1);
        assertThat(treeB2).contains("Stable-");
    }

    @Test
    void vendorMutationInOrgADoesNotAffectOrgB() throws Exception {
        String shared = "MutCo-" + System.nanoTime();
        String vendorA = createVendor(tokenA, shared);
        createVendor(tokenB, shared);

        // Warm both caches.
        mockMvc.perform(get("/api/v1/vendors")
                        .header("Authorization", bearer(tokenA))
                        .param("search", shared))
                .andExpect(status().isOk());
        MvcResult beforeB = mockMvc.perform(get("/api/v1/vendors")
                        .header("Authorization", bearer(tokenB))
                        .param("search", shared))
                .andExpect(status().isOk())
                .andReturn();

        // Deactivate A's vendor.
        mockMvc.perform(delete("/api/v1/vendors/{id}", vendorA)
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isNoContent());

        // A's list no longer contains it; B's cached list still does.
        MvcResult afterA = mockMvc.perform(get("/api/v1/vendors")
                        .header("Authorization", bearer(tokenA))
                        .param("search", shared))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult afterB = mockMvc.perform(get("/api/v1/vendors")
                        .header("Authorization", bearer(tokenB))
                        .param("search", shared))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(afterA.getResponse().getContentAsString())
                .get("content").size()).isEqualTo(0);
        assertThat(afterB.getResponse().getContentAsString())
                .isEqualTo(beforeB.getResponse().getContentAsString());
    }

    @Test
    void unauthenticatedRequestsCannotAccessCachedBusinessData() throws Exception {
        String shared = "Secret-" + System.nanoTime();
        createAccount(tokenA, "9301", shared);
        // Warm the cache as an authenticated member.
        getTree(tokenA);

        // The same cached data must not leak to unauthenticated callers.
        mockMvc.perform(get("/api/v1/accounts/tree"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/vendors"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/customers"))
                .andExpect(status().isUnauthorized());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
