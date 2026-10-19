package com.example.accounting.assistant;

import com.example.accounting.assistant.dto.AssistantChatRequest;
import com.example.accounting.assistant.dto.AssistantChatResponse;
import com.example.accounting.security.AuthContext;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only AI financial assistant. Any authenticated organization member
 * may ask questions; the assistant only explains recorded figures and can
 * never mutate accounting data.
 */
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final FinancialAssistantService assistantService;

    public AssistantController(FinancialAssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping("/chat")
    public ResponseEntity<AssistantChatResponse> chat(@Valid @RequestBody AssistantChatRequest request) {
        UUID userId = AuthContext.currentUserId();
        return ResponseEntity.ok(assistantService.chat(userId, request.message()));
    }
}
