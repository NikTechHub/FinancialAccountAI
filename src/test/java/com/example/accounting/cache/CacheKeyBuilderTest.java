package com.example.accounting.cache;

import com.example.accounting.entity.AccountType;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheKeyBuilderTest {

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();
    private final Pageable pageable = PageRequest.of(0, 20);

    @Test
    void accountTreeKeysAreTenantScoped() {
        String keyA = CacheKeyBuilder.accountTree(List.of(orgA));
        String keyB = CacheKeyBuilder.accountTree(List.of(orgB));

        assertThat(keyA).startsWith("org:");
        assertThat(keyA).contains(orgA.toString());
        assertThat(keyA).doesNotContain(orgB.toString());
        assertThat(keyB).contains(orgB.toString());
        assertThat(keyA).isNotEqualTo(keyB);
    }

    @Test
    void accountListKeyIncludesEveryResultShapingParameter() {
        String base = CacheKeyBuilder.accountList(List.of(orgA), null, null, null, pageable);
        String withSearch = CacheKeyBuilder.accountList(List.of(orgA), "cash", null, null, pageable);
        String withType = CacheKeyBuilder.accountList(List.of(orgA), null, AccountType.ASSET, null, pageable);
        UUID parent = UUID.randomUUID();
        String withParent = CacheKeyBuilder.accountList(List.of(orgA), null, null, parent, pageable);
        String page2 = CacheKeyBuilder.accountList(List.of(orgA), null, null, null, PageRequest.of(1, 20));

        assertThat(withSearch).isNotEqualTo(base);
        assertThat(withType).isNotEqualTo(base);
        assertThat(withParent).isNotEqualTo(base);
        assertThat(page2).isNotEqualTo(base);
        // Same parameters produce the same key (cacheable).
        assertThat(CacheKeyBuilder.accountList(List.of(orgA), "cash", null, null, pageable))
                .isEqualTo(withSearch);
    }

    @Test
    void sameSearchAcrossTwoOrgsProducesDifferentKeys() {
        String keyA = CacheKeyBuilder.accountList(List.of(orgA), "Cash", null, null, pageable);
        String keyB = CacheKeyBuilder.accountList(List.of(orgB), "Cash", null, null, pageable);
        assertThat(keyA).isNotEqualTo(keyB);
        assertThat(keyA).contains(orgA.toString());
        assertThat(keyB).contains(orgB.toString());
    }

    @Test
    void vendorAndCustomerListKeysAreTenantScopedAndParamSensitive() {
        String vA = CacheKeyBuilder.vendorList(List.of(orgA), "ABC", pageable);
        String vB = CacheKeyBuilder.vendorList(List.of(orgB), "ABC", pageable);
        assertThat(vA).isNotEqualTo(vB);
        assertThat(vA).startsWith("org:");
        assertThat(CacheKeyBuilder.vendorList(List.of(orgA), null, pageable)).isNotEqualTo(vA);

        String cA = CacheKeyBuilder.customerList(List.of(orgA), "XYZ", pageable);
        String cB = CacheKeyBuilder.customerList(List.of(orgB), "XYZ", pageable);
        assertThat(cA).isNotEqualTo(cB);
        assertThat(cA).startsWith("org:");
    }

    @Test
    void itemAndOrganizationKeysContainOrgId() {
        UUID accountId = UUID.randomUUID();
        assertThat(CacheKeyBuilder.accountItem(orgA, accountId))
                .startsWith("org:" + orgA + ":");
        assertThat(CacheKeyBuilder.accountItem(orgA, accountId))
                .isNotEqualTo(CacheKeyBuilder.accountItem(orgB, accountId));

        assertThat(CacheKeyBuilder.organization(orgA)).startsWith("org:" + orgA + ":");
        assertThat(CacheKeyBuilder.organization(orgA)).isNotEqualTo(CacheKeyBuilder.organization(orgB));
    }

    @Test
    void nullOrganizationIsRejected() {
        assertThatThrownBy(() -> CacheKeyBuilder.accountItem(null, UUID.randomUUID()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CacheKeyBuilder.accountTree(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CacheKeyBuilder.accountTree(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orgIdOrderingDoesNotFragmentCache() {
        // Same membership set in different order must map to one key.
        String k1 = CacheKeyBuilder.accountTree(List.of(orgA, orgB));
        String k2 = CacheKeyBuilder.accountTree(List.of(orgB, orgA));
        assertThat(k1).isEqualTo(k2);
    }
}
