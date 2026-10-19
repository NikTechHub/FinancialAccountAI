package com.example.accounting.controller;

import com.example.accounting.dto.DocumentResponse;
import com.example.accounting.entity.DocumentStatus;
import com.example.accounting.entity.DocumentType;
import com.example.accounting.security.AuthContext;
import com.example.accounting.service.DocumentService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentType") DocumentType documentType,
            @RequestParam(value = "invoiceId", required = false) UUID invoiceId,
            @RequestParam(value = "expenseId", required = false) UUID expenseId) {
        UUID userId = AuthContext.currentUserId();
        DocumentResponse response = documentService.uploadDocument(userId, file, documentType, invoiceId, expenseId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> get(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        DocumentResponse response = documentService.getDocument(userId, id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        DocumentService.DocumentDownloadData data = documentService.downloadDocument(userId, id);
        InputStreamResource resource = new InputStreamResource(data.inputStream());

        String encodedFilename = URLEncoder.encode(data.originalFileName(), StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        String contentDisposition = "attachment; filename=\"" + data.originalFileName().replace("\"", "") + "\"; filename*=UTF-8''" + encodedFilename;

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(data.contentType()))
                .contentLength(data.fileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(resource);
    }

    @GetMapping
    public ResponseEntity<Page<DocumentResponse>> list(
            @RequestParam(required = false) DocumentType documentType,
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) UUID invoiceId,
            @RequestParam(required = false) UUID expenseId,
            @RequestParam(required = false) String originalFileName,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID userId = AuthContext.currentUserId();
        Page<DocumentResponse> page = documentService.listDocuments(userId, documentType, status, invoiceId, expenseId, originalFileName, pageable);
        return ResponseEntity.ok(page);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        documentService.deleteDocument(userId, id);
        return ResponseEntity.noContent().build();
    }
}
