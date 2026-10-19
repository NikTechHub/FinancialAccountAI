package com.example.accounting.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * CORS is disabled by default. Browser access must be enabled explicitly with
 * {@code APP_CORS_ALLOWED_ORIGINS} (comma-separated exact origins). Wildcards
 * are rejected so a misconfiguration cannot silently allow every origin.
 *
 * <p>The API is a stateless JWT API that sends credentials in the
 * {@code Authorization} header, so cookie credentials are never allowed.</p>
 */
@Configuration
public class CorsConfig {

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        this.allowedOrigins = parseAllowedOrigins(allowedOrigins);
        if (this.allowedOrigins.contains("*")) {
            throw new IllegalStateException(
                    "app.cors.allowed-origins must list explicit origins; wildcard '*' is not allowed");
        }
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (allowedOrigins.isEmpty()) {
            // No CORS mappings: cross-origin browser requests receive no CORS headers.
            return source;
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With"));
        configuration.setExposedHeaders(List.of("Location"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    static List<String> parseAllowedOrigins(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .distinct()
                .toList();
    }
}
