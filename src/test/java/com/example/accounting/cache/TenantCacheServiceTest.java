package com.example.accounting.cache;

import com.example.accounting.dto.AccountTreeResponse;
import com.example.accounting.entity.AccountType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class TenantCacheServiceTest {

    @Autowired
    private TenantCacheService tenantCache;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private ObjectMapper objectMapper;

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    @BeforeEach
    void clearCaches() {
        tenantCache.clearAll();
    }

    @Test
    void nonTenantSafeKeysAreRejected() {
        assertThatThrownBy(() -> tenantCache.get(CacheNames.ACCOUNTS_TREE, "accounts:tree", String.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tenantCache.put(CacheNames.ACCOUNTS_TREE, "vendors:list", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tenantCache.evict(CacheNames.ACCOUNTS_TREE, "plain-key"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void putThenGetReturnsValue() {
        String key = CacheKeyBuilder.organization(orgA);
        var response = new com.example.accounting.dto.OrganizationResponse(
                orgA, "Acme", null, null, null, null, null, null, null);
        tenantCache.put(CacheNames.ORGANIZATIONS, key, response);

        Optional<com.example.accounting.dto.OrganizationResponse> hit =
                tenantCache.get(CacheNames.ORGANIZATIONS, key,
                        com.example.accounting.dto.OrganizationResponse.class);
        assertThat(hit).isPresent();
        assertThat(hit.get().name()).isEqualTo("Acme");
    }

    @Test
    void missReturnsEmpty() {
        Optional<String> miss = tenantCache.get(CacheNames.ORGANIZATIONS,
                CacheKeyBuilder.organization(UUID.randomUUID()), String.class);
        assertThat(miss).isEmpty();
    }

    @Test
    void orgACacheHitDoesNotSatisfyOrgB() {
        String keyA = CacheKeyBuilder.accountTree(List.of(orgA));
        List<AccountTreeResponse> treeA = List.of(
                new AccountTreeResponse(UUID.randomUUID(), "1000", "Cash A", AccountType.ASSET, true));
        tenantCache.put(CacheNames.ACCOUNTS_TREE, keyA, treeA);

        Optional<List<AccountTreeResponse>> hitB = tenantCache.get(CacheNames.ACCOUNTS_TREE,
                CacheKeyBuilder.accountTree(List.of(orgB)),
                new TypeReference<List<AccountTreeResponse>>() {
                });
        assertThat(hitB).isEmpty();

        Optional<List<AccountTreeResponse>> hitA = tenantCache.get(CacheNames.ACCOUNTS_TREE, keyA,
                new TypeReference<List<AccountTreeResponse>>() {
                });
        assertThat(hitA).isPresent();
        assertThat(hitA.get()).hasSize(1);
        assertThat(hitA.get().get(0).name()).isEqualTo("Cash A");
    }

    @Test
    void evictOrgCategoryKeepsOtherOrgsIntact() {
        String keyA = CacheKeyBuilder.vendorList(List.of(orgA), null,
                org.springframework.data.domain.PageRequest.of(0, 20));
        String keyB = CacheKeyBuilder.vendorList(List.of(orgB), null,
                org.springframework.data.domain.PageRequest.of(0, 20));
        tenantCache.put(CacheNames.VENDORS_LIST, keyA, CachedPage.from(
                new org.springframework.data.domain.PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 20), 0)));
        tenantCache.put(CacheNames.VENDORS_LIST, keyB, CachedPage.from(
                new org.springframework.data.domain.PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 20), 0)));

        tenantCache.evictOrgCategory(orgA, CacheNames.VENDORS_LIST);

        assertThat(tenantCache.get(CacheNames.VENDORS_LIST, keyA,
                new TypeReference<CachedPage<com.example.accounting.dto.VendorResponse>>() {
                })).isEmpty();
        assertThat(tenantCache.get(CacheNames.VENDORS_LIST, keyB,
                new TypeReference<CachedPage<com.example.accounting.dto.VendorResponse>>() {
                })).isPresent();
    }

    @Test
    void evictOrgAllDoesNotTouchOtherOrg() {
        String treeA = CacheKeyBuilder.accountTree(List.of(orgA));
        String treeB = CacheKeyBuilder.accountTree(List.of(orgB));
        tenantCache.put(CacheNames.ACCOUNTS_TREE, treeA, List.of());
        tenantCache.put(CacheNames.ACCOUNTS_TREE, treeB, List.of());

        tenantCache.evictOrgAll(orgA);

        assertThat(tenantCache.get(CacheNames.ACCOUNTS_TREE, treeA,
                new TypeReference<List<AccountTreeResponse>>() {
                })).isEmpty();
        assertThat(tenantCache.get(CacheNames.ACCOUNTS_TREE, treeB,
                new TypeReference<List<AccountTreeResponse>>() {
                })).isPresent();
    }

    @Test
    void inMemoryFallbackManagerIsUsedWhenRedisDisabled() {
        // app.redis.enabled=false in test profile: no Redis server required.
        assertThat(cacheManager).isNotNull();
        assertThat(cacheManager.getCache(CacheNames.ACCOUNTS_TREE)).isNotNull();
    }
}
