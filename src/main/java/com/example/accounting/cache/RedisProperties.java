package com.example.accounting.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis / cache TTL configuration.
 * Redis is a performance layer only; the database remains the source of truth.
 * All values are environment-driven with sensible local defaults.
 */
@ConfigurationProperties(prefix = "app.redis")
public class RedisProperties {

    /**
     * Master kill-switch. When false, no Redis connection is required and
     * services fall back to database access with an in-memory cache.
     */
    private boolean enabled = false;

    private final Ttl ttl = new Ttl();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Ttl getTtl() {
        return ttl;
    }

    public static class Ttl {
        /** Organization metadata cache TTL in seconds (default ~10 min). */
        private long organizationSeconds = 600;
        /** Chart-of-accounts tree cache TTL in seconds (default ~10 min). */
        private long accountTreeSeconds = 600;
        /** Chart-of-accounts list/search cache TTL in seconds (default ~10 min). */
        private long accountListSeconds = 600;
        /** Single account lookup TTL in seconds. */
        private long accountItemSeconds = 600;
        /** Vendor list cache TTL in seconds (default ~5 min). */
        private long vendorListSeconds = 300;
        /** Single vendor lookup TTL in seconds. */
        private long vendorItemSeconds = 300;
        /** Customer list cache TTL in seconds (default ~5 min). */
        private long customerListSeconds = 300;
        /** Single customer lookup TTL in seconds. */
        private long customerItemSeconds = 300;

        public long getOrganizationSeconds() {
            return organizationSeconds;
        }

        public void setOrganizationSeconds(long organizationSeconds) {
            this.organizationSeconds = organizationSeconds;
        }

        public long getAccountTreeSeconds() {
            return accountTreeSeconds;
        }

        public void setAccountTreeSeconds(long accountTreeSeconds) {
            this.accountTreeSeconds = accountTreeSeconds;
        }

        public long getAccountListSeconds() {
            return accountListSeconds;
        }

        public void setAccountListSeconds(long accountListSeconds) {
            this.accountListSeconds = accountListSeconds;
        }

        public long getAccountItemSeconds() {
            return accountItemSeconds;
        }

        public void setAccountItemSeconds(long accountItemSeconds) {
            this.accountItemSeconds = accountItemSeconds;
        }

        public long getVendorListSeconds() {
            return vendorListSeconds;
        }

        public void setVendorListSeconds(long vendorListSeconds) {
            this.vendorListSeconds = vendorListSeconds;
        }

        public long getVendorItemSeconds() {
            return vendorItemSeconds;
        }

        public void setVendorItemSeconds(long vendorItemSeconds) {
            this.vendorItemSeconds = vendorItemSeconds;
        }

        public long getCustomerListSeconds() {
            return customerListSeconds;
        }

        public void setCustomerListSeconds(long customerListSeconds) {
            this.customerListSeconds = customerListSeconds;
        }

        public long getCustomerItemSeconds() {
            return customerItemSeconds;
        }

        public void setCustomerItemSeconds(long customerItemSeconds) {
            this.customerItemSeconds = customerItemSeconds;
        }
    }
}
