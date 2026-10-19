package com.example.accounting.repository;

import com.example.accounting.config.JpaAuditingConfig;
import com.example.accounting.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldSaveAndFindUser() {
        User user = new User("John", "Doe", "john.doe@example.com", "hashedPassword123", true);
        User saved = userRepository.save(user);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getEmail()).isEqualTo("john.doe@example.com");

        assertThat(userRepository.findByEmail("john.doe@example.com")).isPresent();
        assertThat(userRepository.existsByEmail("john.doe@example.com")).isTrue();
    }

    @Test
    void shouldEnforceUniqueEmail() {
        User u1 = new User("Alice", "Smith", "alice@example.com", "hash1", true);
        userRepository.saveAndFlush(u1);

        User u2 = new User("Bob", "Jones", "alice@example.com", "hash2", true);
        try {
            userRepository.saveAndFlush(u2);
            assertThat(false).as("Expected duplicate email violation").isTrue();
        } catch (Exception ex) {
            assertThat(ex).isNotNull();
        }
    }

    @Test
    void shouldNormalizeActiveDefault() {
        User user = new User("Jane", "Doe", "jane@example.com", "hash", null);
        User saved = userRepository.save(user);
        // active defaults to true in constructor, but DB default also true
        assertThat(saved.getActive()).isTrue();
    }
}
