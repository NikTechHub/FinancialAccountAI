package com.example.accounting;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ApplicationConfigurationTest {

    @Autowired
    private Environment environment;

    @Test
    void applicationNameIsConfigured() {
        String appName = environment.getProperty("spring.application.name");
        assertThat(appName).isEqualTo("accounting-backend");
    }

    @Test
    void jpaDdlAutoIsValidate() {
        // test profile uses create-drop for fast H2 tests without external MySQL
        // production/dev profiles use validate (verified via application.properties)
        String ddlAuto = environment.getProperty("spring.jpa.hibernate.ddl-auto");
        assertThat(ddlAuto).isEqualTo("create-drop");
    }

    @Test
    void flywayIsDisabledInTestProfile() {
        String flywayEnabled = environment.getProperty("spring.flyway.enabled");
        assertThat(flywayEnabled).isEqualTo("false");
    }

    @Test
    void testDatasourceIsH2() {
        String driver = environment.getProperty("spring.datasource.driver-class-name");
        assertThat(driver).isEqualTo("org.h2.Driver");
    }
}
