package com.example.accounting.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtSecretValidatorTest {

    private static JwtProperties productionProperties(String secret) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setRequireStrongSecret(true);
        return properties;
    }

    @Test
    void productionRejectsMissingSecret() {
        JwtSecretValidator validator = new JwtSecretValidator(productionProperties(null));

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET is required");
    }

    @Test
    void productionRejectsBlankSecret() {
        JwtSecretValidator validator = new JwtSecretValidator(productionProperties("   "));

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET is required");
    }

    @Test
    void productionRejectsShortSecret() {
        String shortSecret = Base64.getEncoder()
                .encodeToString("too-short".getBytes(StandardCharsets.UTF_8));
        JwtSecretValidator validator = new JwtSecretValidator(productionProperties(shortSecret));

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void productionRejectsKnownDevelopmentDefault() {
        JwtSecretValidator validator = new JwtSecretValidator(productionProperties(
                "QmFzZTY0U2VjcmV0Rm9ySldUU2lnbmluZ1Rlc3RTZWNyZXRUaGF0SXNTZWN1cmU="));

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("development default");
    }

    @Test
    void productionRejectsKnownTestDefault() {
        JwtSecretValidator validator = new JwtSecretValidator(productionProperties(
                "dGVzdC1zZWNyZXQta2V5LXRoYXQtaXMtMzItYnl0ZXMtbG9uZy1mb3ItaHNtYWM="));

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("development default");
    }

    @Test
    void productionRejectsPlaceholderValues() {
        JwtSecretValidator validator = new JwtSecretValidator(productionProperties(
                "change-me-generate-with-openssl-rand-base64-48"));

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    void productionAcceptsStrongBase64Secret() {
        String strongSecret = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        JwtSecretValidator validator = new JwtSecretValidator(productionProperties(strongSecret));

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void productionAcceptsStrongRawSecret() {
        // Contains a non-Base64 character, so it is treated as raw UTF-8 bytes.
        JwtSecretValidator validator = new JwtSecretValidator(productionProperties(
                "0123456789abcdef0123456789abcde!"));

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void developmentProfileDoesNotRequireStrongSecret() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("dev");
        properties.setRequireStrongSecret(false);

        assertThatCode(() -> new JwtSecretValidator(properties).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
