package com.example.accounting.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the checked-in production profile without starting a MySQL context.
 */
class ProductionConfigurationTest {

    private Properties productionProperties() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/application-prod.properties")) {
            assertThat(input).isNotNull();
            properties.load(input);
        }
        return properties;
    }

    @Test
    void productionUsesMySqlAndFlywayWithValidateMode() throws Exception {
        Properties properties = productionProperties();

        assertThat(properties.getProperty("spring.datasource.url")).startsWith("jdbc:mysql:");
        assertThat(properties.getProperty("spring.datasource.username")).isEqualTo("${DB_USERNAME}");
        assertThat(properties.getProperty("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
        assertThat(properties.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(properties.getProperty("spring.jpa.show-sql")).isEqualTo("false");
        assertThat(properties.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(properties.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
        assertThat(properties.getProperty("spring.flyway.validate-on-migrate")).isEqualTo("true");
    }

    @Test
    void productionRequiresJwtSecretAndRejectsWeakSecrets() throws Exception {
        Properties properties = productionProperties();

        assertThat(properties.getProperty("app.jwt.secret")).isEqualTo("${JWT_SECRET:}");
        assertThat(properties.getProperty("app.jwt.require-strong-secret")).isEqualTo("true");
    }

    @Test
    void productionKeepsRedisAndKafkaOptionalWithSafeLocalDefaults() throws Exception {
        Properties properties = productionProperties();

        assertThat(properties.getProperty("app.redis.enabled")).isEqualTo("${REDIS_ENABLED:false}");
        assertThat(properties.getProperty("spring.data.redis.host"))
                .isEqualTo("${REDIS_HOST:localhost}");
        assertThat(properties.getProperty("app.kafka.enabled")).isEqualTo("${KAFKA_ENABLED:true}");
        assertThat(properties.getProperty("app.kafka.bootstrap-servers"))
                .isEqualTo("${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}");
    }

    @Test
    void productionDoesNotEnablePermissiveCorsByDefault() throws Exception {
        Properties properties = productionProperties();

        assertThat(properties.getProperty("app.cors.allowed-origins"))
                .isEqualTo("${APP_CORS_ALLOWED_ORIGINS:}");
    }

    @Test
    void productionExposesOnlyHealthActuatorEndpointWithoutDetails() throws Exception {
        Properties properties = productionProperties();

        assertThat(properties.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health");
        assertThat(properties.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("never");
    }

    @Test
    void productionHidesErrorInternalsAndShutsDownGracefully() throws Exception {
        Properties properties = productionProperties();

        assertThat(properties.getProperty("server.error.include-stacktrace")).isEqualTo("never");
        assertThat(properties.getProperty("server.error.include-message")).isEqualTo("never");
        assertThat(properties.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(properties.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
                .isEqualTo("30s");
    }
}
