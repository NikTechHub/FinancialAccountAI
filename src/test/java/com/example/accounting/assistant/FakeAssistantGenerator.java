package com.example.accounting.assistant;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Deterministic test double for the assistant AI layer. No network calls.
 * Tests control its behavior through {@link #mode} (reset before each test).
 */
@Component
@Profile("test")
@org.springframework.context.annotation.Primary
public class FakeAssistantGenerator implements AssistantAiGenerator {

    public enum Mode {
        /** Echo the grounded data with a short natural-language lead-in. */
        ECHO,
        /** Attempt to override a deterministic figure (must be rejected). */
        HALLUCINATE,
        /** Simulate a provider outage (must trigger the fallback). */
        FAIL
    }

    public static volatile Mode mode = Mode.ECHO;

    @Override
    public String generateAnswer(String userMessage, String groundedData) {
        return switch (mode) {
            case FAIL -> throw new RuntimeException("Simulated AI provider failure");
            case HALLUCINATE -> "Your revenue was 999999. This figure comes from my own estimate.";
            case ECHO -> "Based on your recorded figures: " + groundedData;
        };
    }
}
