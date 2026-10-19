package com.example.accounting.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssistantChatRequest(
        @NotBlank(message = "message must not be blank")
        @Size(max = 2000, message = "message must be at most 2000 characters")
        String message
) {
}
