package com.example.accounting.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Spring AI implementation. Reuses the existing provider configuration
 * (same API key / model properties as document extraction). Follows the
 * same lazy-invocation convention: with a dummy key or no chat client
 * available, no network call is attempted and generation is reported as
 * unavailable so the service uses its deterministic fallback.
 */
@Component
public class SpringAiAssistantGenerator implements AssistantAiGenerator {

    private static final Logger log = LoggerFactory.getLogger(SpringAiAssistantGenerator.class);

    private final String apiKey;
    private final String modelName;
    private final Optional<ChatClient.Builder> chatClientBuilder;

    public SpringAiAssistantGenerator(
            @Value("${spring.ai.openai.api-key:dummy}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String modelName,
            Optional<ChatClient.Builder> chatClientBuilder) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.chatClientBuilder = chatClientBuilder;
    }

    @Override
    public String generateAnswer(String userMessage, String groundedData) {
        if (isDummyKey() || chatClientBuilder.isEmpty()) {
            throw new IllegalStateException("AI model unavailable; using deterministic answer");
        }
        try {
            String content = chatClientBuilder.get().build().prompt()
                    .system(AssistantPromptConstants.SYSTEM_PROMPT)
                    .user(AssistantPromptConstants.buildUserPrompt(userMessage, groundedData))
                    .call()
                    .content();
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("Empty response from AI provider");
            }
            return content;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Assistant AI generation failed; falling back to deterministic answer");
            throw new IllegalStateException("AI generation failed", e);
        }
    }

    private boolean isDummyKey() {
        return apiKey == null || apiKey.isBlank()
                || "dummy".equalsIgnoreCase(apiKey.trim()) || apiKey.length() < 10;
    }
}
