package com.example.accounting.cache;

import com.example.accounting.entity.AccountType;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Centralized tenant-safe cache-key strategy.
 *
 * <p>Every key starts with {@code org:{organizationId}:} so cached data from
 * one organization can never be returned to another. There is intentionally
 * no API to build a key without an organization ID, making it difficult to
 * accidentally create a non-tenant-scoped key.</p>
 *
 * <p>Keys must only be built AFTER the organization membership has been
 * validated through the existing authenticated-user membership mechanism.
 * This class does not perform authorization itself; callers must validate
 * first and then call these builders.</p>
 */
public final class CacheKeyBuilder {

    private CacheKeyBuilder() {
    }

    private static String orgPrefix(UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        return "org:" + organizationId + ":";
    }

    /**
     * Deterministic representation of a set of accessible organization IDs.
     * Sorted so the same membership set always produces the same key segment.
     */
    public static String joinOrgIds(List<UUID> organizationIds) {
        Objects.requireNonNull(organizationIds, "organizationIds must not be null");
        if (organizationIds.isEmpty()) {
            throw new IllegalArgumentException("organizationIds must not be empty");
        }
        List<UUID> sorted = new ArrayList<>(organizationIds);
        sorted.sort(Comparator.comparing(UUID::toString));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(sorted.get(i));
        }
        return sb.toString();
    }

    // --- Chart of accounts ---

    public static String accountTree(List<UUID> organizationIds) {
        return "org:" + joinOrgIds(organizationIds) + ":accounts:tree";
    }

    public static String accountList(List<UUID> organizationIds,
                                     String search,
                                     AccountType type,
                                     UUID parentAccountId,
                                     Pageable pageable) {
        String normSearch = search == null ? "" : search.trim();
        String normType = type == null ? "-" : type.name();
        String normParent = parentAccountId == null ? "-" : parentAccountId.toString();
        int page = pageable.isPaged() ? pageable.getPageNumber() : 0;
        int size = pageable.isPaged() ? pageable.getPageSize() : 20;
        String sort = pageable.getSort().toString().replace(" ", "");
        if (sort.isEmpty() || sort.equals("UNSORTED")) {
            sort = "-";
        }
        // Include every parameter that changes the result so two different
        // queries can never share a key.
        return "org:" + joinOrgIds(organizationIds)
                + ":accounts:list:s=" + sanitize(normSearch)
                + ":t=" + normType
                + ":p=" + normParent
                + ":pg=" + page + ":sz=" + size + ":sort=" + sanitize(sort);
    }

    public static String accountItem(UUID organizationId, UUID accountId) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        return orgPrefix(organizationId) + "accounts:item:" + accountId;
    }

    // --- Vendors ---

    public static String vendorList(List<UUID> organizationIds, String search, Pageable pageable) {
        String normSearch = search == null ? "" : search.trim();
        int page = pageable.isPaged() ? pageable.getPageNumber() : 0;
        int size = pageable.isPaged() ? pageable.getPageSize() : 20;
        String sort = pageable.getSort().toString().replace(" ", "");
        if (sort.isEmpty() || sort.equals("UNSORTED")) {
            sort = "-";
        }
        return "org:" + joinOrgIds(organizationIds)
                + ":vendors:list:s=" + sanitize(normSearch)
                + ":pg=" + page + ":sz=" + size + ":sort=" + sanitize(sort);
    }

    public static String vendorItem(UUID organizationId, UUID vendorId) {
        Objects.requireNonNull(vendorId, "vendorId must not be null");
        return orgPrefix(organizationId) + "vendors:item:" + vendorId;
    }

    // --- Customers ---

    public static String customerList(List<UUID> organizationIds, String search, Pageable pageable) {
        String normSearch = search == null ? "" : search.trim();
        int page = pageable.isPaged() ? pageable.getPageNumber() : 0;
        int size = pageable.isPaged() ? pageable.getPageSize() : 20;
        String sort = pageable.getSort().toString().replace(" ", "");
        if (sort.isEmpty() || sort.equals("UNSORTED")) {
            sort = "-";
        }
        return "org:" + joinOrgIds(organizationIds)
                + ":customers:list:s=" + sanitize(normSearch)
                + ":pg=" + page + ":sz=" + size + ":sort=" + sanitize(sort);
    }

    public static String customerItem(UUID organizationId, UUID customerId) {
        Objects.requireNonNull(customerId, "customerId must not be null");
        return orgPrefix(organizationId) + "customers:item:" + customerId;
    }

    // --- Organization metadata ---

    public static String organization(UUID organizationId) {
        return orgPrefix(organizationId) + "organizations:meta";
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        // Keep keys readable but bounded; replace separators that would
        // make keys ambiguous.
        String cleaned = value.replace(":", "_").replace(" ", "_");
        if (cleaned.length() > 120) {
            return cleaned.substring(0, 120) + "#h" + Integer.toHexString(cleaned.hashCode());
        }
        return cleaned;
    }
}
