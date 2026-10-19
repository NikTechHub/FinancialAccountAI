package com.example.accounting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    private String secret;
    private long accessTokenExpiration = 900000L; // 15 min
    private long refreshTokenExpiration = 604800000L; // 7 days
    private boolean refreshTokenRotationEnabled = true;
    /**
     * When true (prod profile), startup fails fast unless the secret decodes
     * to at least 32 bytes and differs from known development defaults.
     * Dev/test profiles leave this false for a convenient local setup.
     */
    private boolean requireStrongSecret = false;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getAccessTokenExpiration() {
        return accessTokenExpiration;
    }

    public void setAccessTokenExpiration(long accessTokenExpiration) {
        this.accessTokenExpiration = accessTokenExpiration;
    }

    public long getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }

    public void setRefreshTokenExpiration(long refreshTokenExpiration) {
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    public boolean isRefreshTokenRotationEnabled() {
        return refreshTokenRotationEnabled;
    }

    public void setRefreshTokenRotationEnabled(boolean refreshTokenRotationEnabled) {
        this.refreshTokenRotationEnabled = refreshTokenRotationEnabled;
    }

    public boolean isRequireStrongSecret() {
        return requireStrongSecret;
    }

    public void setRequireStrongSecret(boolean requireStrongSecret) {
        this.requireStrongSecret = requireStrongSecret;
    }
}
