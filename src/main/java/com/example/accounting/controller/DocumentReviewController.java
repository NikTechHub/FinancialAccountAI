package com.example.accounting.controller;

import com.example.accounting.dto.DocumentReviewResponse;
import com.example.accounting.dto.ReviewActionRequest;
import com.example.accounting.dto.StructuredInvoiceExtraction;
import com.example.accounting.dto.UpdateDocumentReviewRequest;
import com.example.accounting.security.AuthContext;
import com.example.accounting.service.DocumentReviewService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents/{documentId}/review")
public class DocumentReviewController {

    private final DocumentReviewService reviewService;

    public DocumentReviewController(DocumentReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping
    public ResponseEntity<DocumentReviewResponse> createReview(@PathVariable UUID documentId) {
        UUID userId = AuthContext.currentUserId();
        DocumentReviewResponse resp = reviewService.createReview(userId, documentId);
        return ResponseEntity.ok(resp);
    }

    @GetMapping
    public ResponseEntity<DocumentReviewResponse> getReview(@PathVariable UUID documentId) {
        UUID userId = AuthContext.currentUserId();
        DocumentReviewResponse resp = reviewService.getReview(userId, documentId);
        return ResponseEntity.ok(resp);
    }

    @PutMapping
    public ResponseEntity<DocumentReviewResponse> updateReview(@PathVariable UUID documentId,
                                                               @Valid @RequestBody UpdateDocumentReviewRequest request) {
        UUID userId = AuthContext.currentUserId();
        DocumentReviewResponse resp = reviewService.updateReview(userId, documentId, request.reviewedData());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/approve")
    public ResponseEntity<DocumentReviewResponse> approveReview(@PathVariable UUID documentId,
                                                                @RequestBody(required = false) ReviewActionRequest request) {
        UUID userId = AuthContext.currentUserId();
        String comment = request != null ? request.reviewComment() : null;
        DocumentReviewResponse resp = reviewService.approveReview(userId, documentId, comment);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/reject")
    public ResponseEntity<DocumentReviewResponse> rejectReview(@PathVariable UUID documentId,
                                                               @RequestBody(required = false) ReviewActionRequest request) {
        UUID userId = AuthContext.currentUserId();
        String comment = request != null ? request.reviewComment() : null;
        DocumentReviewResponse resp = reviewService.rejectReview(userId, documentId, comment);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/reopen")
    public ResponseEntity<DocumentReviewResponse> reopenReview(@PathVariable UUID documentId) {
        UUID userId = AuthContext.currentUserId();
        DocumentReviewResponse resp = reviewService.reopenReview(userId, documentId);
        return ResponseEntity.ok(resp);
    }
}
