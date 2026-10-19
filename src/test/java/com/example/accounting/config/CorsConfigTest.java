package com.example.accounting.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsConfigTest {

    @Test
    void emptyConfigurationProducesNoAllowedOrigins() {
        assertThat(CorsConfig.parseAllowedOrigins(null)).isEmpty();
        assertThat(CorsConfig.parseAllowedOrigins("   ")).isEmpty();
        assertThat(CorsConfig.parseAllowedOrigins(" , , ")).isEmpty();
    }

    @Test
    void originsAreTrimmedAndDeduplicated() {
        assertThat(CorsConfig.parseAllowedOrigins("https://a.example.com, https://b.example.com,https://a.example.com"))
                .containsExactly("https://a.example.com", "https://b.example.com");
    }

    @Test
    void wildcardOriginsAreRejected() {
        assertThatThrownBy(() -> new CorsConfig("*"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard");
        assertThatThrownBy(() -> new CorsConfig("https://app.example.com,*"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard");
    }
}
