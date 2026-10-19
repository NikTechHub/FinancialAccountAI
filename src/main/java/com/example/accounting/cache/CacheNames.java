package com.example.accounting.cache;

/**
 * Centralized Spring cache names. Never scatter literal cache names
 * across services; always reference these constants.
 */
public final class CacheNames {

    private CacheNames() {
    }

    public static final String ACCOUNTS_TREE = "accounts-tree";
    public static final String ACCOUNTS_LIST = "accounts-list";
    public static final String ACCOUNTS_ITEM = "accounts-item";

    public static final String VENDORS_LIST = "vendors-list";
    public static final String VENDORS_ITEM = "vendors-item";

    public static final String CUSTOMERS_LIST = "customers-list";
    public static final String CUSTOMERS_ITEM = "customers-item";

    public static final String ORGANIZATIONS = "organizations";

    public static String[] all() {
        return new String[]{
                ACCOUNTS_TREE, ACCOUNTS_LIST, ACCOUNTS_ITEM,
                VENDORS_LIST, VENDORS_ITEM,
                CUSTOMERS_LIST, CUSTOMERS_ITEM,
                ORGANIZATIONS
        };
    }
}
