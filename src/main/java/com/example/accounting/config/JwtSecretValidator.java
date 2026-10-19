package com.example.accounting.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-fast validation for the production JWT signing secret.
 *
 * <p>When {@code app.jwt.require-strong-secret=true} (the {@code prod} profile),
 * the application refuses to start if {@code JWT_SECRET} is missing, still uses
 * one of the known development/test defaults, looks like a placeholder, or
 * decodes to fewer than 32 bytes (the HS256 minimum used by {@link io.jsonwebtoken.security.Keys}).</p>
 *
 * <p>Development and test profiles keep {@code require-strong-secret=false} so
 * local setup and the unit-test suite remain convenient and Docker-free.</p>
 */
@Component
public class JwtSecretValidator implements InitializingBean {

    static final int MIN_SECRET_BYTES = 32;

    private static final Logger log = LoggerFactory.getLogger(JwtSecretValidator.class);

    private static final Set<String> KNOWN_INSECURE_SECRETS = Set.of(
            // Base application / dev fallback
            "QmFzZTY0U2VjcmV0Rm9ySldUU2lnbmluZ1Rlc3RTZWNyZXRUaGF0SXNTZWN1cmU=",
            // Test-profile fallback
            "dGVzdC1zZWNyZXQta2V5LXRoYXQtaXMtMzItYnl0ZXMtbG9uZy1mb3ItaHNtYWM="
    );

    private static final List<String> PLACEHOLDER_MARKERS = List.of(
            "change-me", "changeme", "replace-me", "replaceme",
            "your-secret", "yoursecret", "dev-secret", "test-secret",
            "example-secret", "dummy-secret", "placeholder"
    );

    private final JwtProperties jwtProperties;

    public JwtSecretValidator(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!jwtProperties.isRequireStrongSecret()) {
            log.debug("JWT strong-secret validation disabled for this profile");
            return;
        }

        String secret = jwtProperties.getSecret();
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException(
                    "JWT_SECRET is required when app.jwt.require-strong-secret=true. "
                            + "Configure a Base64-encoded random secret of at least 32 bytes.");
        }

        String normalized = secret.trim();
        String lowerCase = normalized.toLowerCase(Locale.ROOT);
        boolean knownDefault = KNOWN_INSECURE_SECRETS.contains(normalized);
        boolean placeholder = PLACEHOLDER_MARKERS.stream().anyMatch(lowerCase::contains);
        if (knownDefault || placeholder) {
            throw new IllegalStateException(
                    "JWT_SECRET uses a development default or placeholder value. "
                            + "Generate a unique random secret for production.");
        }

        byte[] keyBytes = decodeSecretBytes(normalized);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must decode to at least " + MIN_SECRET_BYTES + " bytes. "
                            + "Generate a Base64-encoded random secret of at least 32 bytes.");
        }

        log.info("JWT secret strength validation passed");
    }

    private byte[] decodeSecretBytes(String secret) {
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }
}
