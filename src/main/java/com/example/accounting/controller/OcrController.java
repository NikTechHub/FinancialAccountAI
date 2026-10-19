package com.example.accounting.controller;

import com.example.accounting.dto.OcrResultResponse;
import com.example.accounting.dto.ProcessingAcceptedResponse;
import com.example.accounting.security.AuthContext;
import com.example.accounting.service.OcrService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents/{documentId}/ocr")
public class OcrController {

    private final OcrService ocrService;

    public OcrController(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    @PostMapping
    public ResponseEntity<ProcessingAcceptedResponse> startOcr(@PathVariable UUID documentId) {
        UUID userId = AuthContext.currentUserId();
        ProcessingAcceptedResponse response = ocrService.requestOcr(userId, documentId);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping
    public ResponseEntity<OcrResultResponse> getOcr(@PathVariable UUID documentId) {
        UUID userId = AuthContext.currentUserId();
        OcrResultResponse response = ocrService.getOcrResult(userId, documentId);
        return ResponseEntity.ok(response);
    }
}
