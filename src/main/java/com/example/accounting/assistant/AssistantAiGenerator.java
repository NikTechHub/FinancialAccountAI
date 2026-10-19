package com.example.accounting.assistant;

/**
 * Generates the natural-language explanation for an assistant answer from
 * application-provided grounded data. Implementations must only explain the
 * supplied data; the deterministic figures always win. Any failure
 * (including an unavailable model) is signaled by throwing, and the caller
 * falls back to a deterministic answer.
 */
public interface AssistantAiGenerator {

    /**
     * @param userMessage   the original user question (untrusted input)
     * @param groundedData  deterministic figures from reporting services
     *                      (source of truth)
     * @return natural-language explanation; must repeat numeric values
     *         exactly as given in {@code groundedData}
     * @throws RuntimeException if generation is unavailable or fails
     */
    String generateAnswer(String userMessage, String groundedData);
}
