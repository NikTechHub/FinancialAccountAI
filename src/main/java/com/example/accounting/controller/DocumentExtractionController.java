package com.example.accounting.controller;

import com.example.accounting.dto.DocumentExtractionResponse;
import com.example.accounting.dto.ProcessingAcceptedResponse;
import com.example.accounting.security.AuthContext;
import com.example.accounting.service.DocumentExtractionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents/{documentId}/extraction")
public class DocumentExtractionController {

    private final DocumentExtractionService extractionService;

    public DocumentExtractionController(DocumentExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    @PostMapping
    public ResponseEntity<ProcessingAcceptedResponse> startExtraction(@PathVariable UUID documentId) {
        UUID userId = AuthContext.currentUserId();
        ProcessingAcceptedResponse response = extractionService.requestExtraction(userId, documentId);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping
    public ResponseEntity<DocumentExtractionResponse> getExtraction(@PathVariable UUID documentId) {
        UUID userId = AuthContext.currentUserId();
        DocumentExtractionResponse response = extractionService.getExtraction(userId, documentId);
        return ResponseEntity.ok(response);
    }
}
