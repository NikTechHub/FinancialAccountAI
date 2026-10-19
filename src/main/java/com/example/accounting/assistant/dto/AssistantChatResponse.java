package com.example.accounting.assistant.dto;

import java.util.List;

public record AssistantChatResponse(
        String answer,
        boolean dataUsed,
        AssistantPeriod period,
        List<String> sources
) {
}
