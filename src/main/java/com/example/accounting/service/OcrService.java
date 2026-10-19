package com.example.accounting.service;

import com.example.accounting.audit.AuditAction;
import com.example.accounting.audit.AuditCommand;
import com.example.accounting.audit.AuditLogService;
import com.example.accounting.audit.AuditResourceType;
import com.example.accounting.audit.AuditSnapshots;
import com.example.accounting.dto.OcrResultResponse;
import com.example.accounting.dto.ProcessingAcceptedResponse;
import com.example.accounting.entity.*;
import com.example.accounting.exception.DocumentNotFoundException;
import com.example.accounting.exception.OcrAlreadyProcessingException;
import com.example.accounting.exception.OcrEngineUnavailableException;
import com.example.accounting.exception.OcrUnsupportedDocumentException;
import com.example.accounting.kafka.DocumentProcessingEventPublisher;
import com.example.accounting.kafka.KafkaProperties;
import com.example.accounting.kafka.event.DocumentOcrRequestedEvent;
import com.example.accounting.ocr.OcrData;
import com.example.accounting.ocr.OcrException;
import com.example.accounting.ocr.OcrProvider;
import com.example.accounting.repository.DocumentRepository;
import com.example.accounting.repository.OcrResultRepository;
import com.example.accounting.repository.OrganizationMembershipRepository;
import com.example.accounting.security.OrganizationAccessService;
import com.example.accounting.storage.DocumentStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private final DocumentRepository documentRepository;
    private final OcrResultRepository ocrResultRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationAccessService organizationAccessService;
    private final DocumentStorageService storageService;
    private final OcrProvider ocrProvider;
    private final DocumentProcessingEventPublisher eventPublisher;
    private final KafkaProperties kafkaProperties;
    private final AuditLogService auditLogService;

    public OcrService(DocumentRepository documentRepository,
                      OcrResultRepository ocrResultRepository,
                      OrganizationMembershipRepository membershipRepository,
                      OrganizationAccessService organizationAccessService,
                      DocumentStorageService storageService,
                      OcrProvider ocrProvider,
                      DocumentProcessingEventPublisher eventPublisher,
                      KafkaProperties kafkaProperties,
                      AuditLogService auditLogService) {
        this.documentRepository = documentRepository;
        this.ocrResultRepository = ocrResultRepository;
        this.membershipRepository = membershipRepository;
        this.organizationAccessService = organizationAccessService;
        this.storageService = storageService;
        this.ocrProvider = ocrProvider;
        this.eventPublisher = eventPublisher;
        this.kafkaProperties = kafkaProperties;
        this.auditLogService = auditLogService;
    }

    private List<UUID> accessibleOrganizationIds(UUID userId) {
        return membershipRepository.findByUserId(userId).stream()
                .map(m -> m.getOrganization().getId())
                .collect(Collectors.toList());
    }

    private void requireReadPermission(UUID userId, UUID orgId) {
        if (!membershipRepository.existsByUserIdAndOrganizationId(userId, orgId)) {
            throw new AccessDeniedException("No membership");
        }
    }

    private void requireOcrPermission(UUID userId, UUID orgId) {
        var roleOpt = organizationAccessService.userRole(userId, orgId);
        var role = roleOpt.orElseThrow(() -> new AccessDeniedException("No membership"));
        if (role == MembershipRole.VIEWER || role == MembershipRole.EMPLOYEE) {
            throw new AccessDeniedException("Role " + role + " cannot start OCR");
        }
    }

    @Transactional
    public OcrResultResponse startOcr(UUID userId, UUID documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!accessibleOrganizationIds(userId).contains(doc.getOrganization().getId())) {
            throw new DocumentNotFoundException(documentId);
        }
        requireOcrPermission(userId, doc.getOrganization().getId());

        // Validate supported content type
        String contentType = doc.getContentType() != null ? doc.getContentType().toLowerCase() : "";
        if (!contentType.equals("application/pdf") && !contentType.startsWith("image/")) {
            throw new OcrUnsupportedDocumentException("Unsupported document type for OCR: " + contentType);
        }
        // Allow pdf, jpeg, png, webp via existing allowed types; if not allowed, we already validated at upload, but double-check
        if (!List.of("application/pdf", "image/jpeg", "image/png", "image/webp").contains(contentType)) {
            // For OCR we support same as upload, but be lenient for jpeg variations
            if (!contentType.equals("image/jpg") && !contentType.equals("image/jpeg") && !contentType.equals("image/png") && !contentType.equals("application/pdf") && !contentType.equals("image/webp")) {
                throw new OcrUnsupportedDocumentException("Unsupported content type: " + contentType);
            }
        }

        // Check existing OCR result
        var existingOpt = ocrResultRepository.findByDocumentId(documentId);
        OcrResult ocrResult;
        if (existingOpt.isPresent()) {
            ocrResult = existingOpt.get();
            if (ocrResult.getStatus() == OcrStatus.PROCESSING) {
                throw new OcrAlreadyProcessingException("OCR already in progress for document: " + documentId);
            }
            // For COMPLETED or FAILED or PENDING, we reset and reprocess
            ocrResult.setStatus(OcrStatus.PROCESSING);
            ocrResult.setProcessingStartedAt(Instant.now());
            ocrResult.setProcessingCompletedAt(null);
            ocrResult.setErrorMessage(null);
            ocrResult.setExtractedText(null);
            ocrResult.setPageCount(null);
        } else {
            ocrResult = new OcrResult(doc, OcrStatus.PROCESSING);
            ocrResult.setProcessingStartedAt(Instant.now());
            // id will be generated
        }

        // Update document status to PROCESSING
        doc.setStatus(DocumentStatus.PROCESSING);
        documentRepository.save(doc);
        ocrResult = ocrResultRepository.save(ocrResult);

        // Now perform OCR synchronously
        try {
            // Check if provider available
            if (!ocrProvider.isAvailable()) {
                // For FakeOcrProvider, isAvailable always true. For real Tesseract, if not available, we fail gracefully
                // But we still try to proceed; TesseractOcrProvider will handle fallback
                // If truly unavailable and we want to fail, we could throw
                // For now, we allow fallback processing
            }

            // Retrieve file to temp location
            Path tempFile = null;
            try {
                // Get file from storage via retrieveAsPath or via stream
                // Use storageService to get InputStream and copy to temp file
                String storageKey = doc.getStorageKey();
                // For local storage, we can get path directly
                Path tempDir = Files.createTempDirectory("ocr-");
                String ext = getExtension(doc.getOriginalFileName(), doc.getContentType());
                tempFile = Files.createTempFile(tempDir, "doc-", "." + ext);
                try (InputStream is = storageService.retrieve(storageKey)) {
                    Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
                }

                OcrData data = ocrProvider.extractText(tempFile, doc.getContentType(), doc.getOriginalFileName());

                // Success
                ocrResult.setExtractedText(data.extractedText());
                ocrResult.setPageCount(data.pageCount());
                ocrResult.setStatus(OcrStatus.COMPLETED);
                ocrResult.setProcessingCompletedAt(Instant.now());
                ocrResult.setErrorMessage(null);

                doc.setStatus(DocumentStatus.PROCESSED);
                documentRepository.save(doc);
                ocrResult = ocrResultRepository.save(ocrResult);

                return toResponse(ocrResult);

            } finally {
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                        // delete parent temp dir if empty
                        Path parent = tempFile.getParent();
                        if (parent != null && Files.isDirectory(parent)) {
                            try {
                                if (Files.list(parent).findAny().isEmpty()) {
                                    Files.deleteIfExists(parent);
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                }
            }

        } catch (OcrException e) {
            // OCR failure
            String sanitizedError = sanitizeError(e.getMessage());
            ocrResult.setStatus(OcrStatus.FAILED);
            ocrResult.setErrorMessage(sanitizedError);
            ocrResult.setProcessingCompletedAt(Instant.now());
            ocrResult.setExtractedText(null);
            ocrResult.setPageCount(null);
            ocrResultRepository.save(ocrResult);

            doc.setStatus(DocumentStatus.FAILED);
            documentRepository.save(doc);

            // Do not expose stack trace, just return result with FAILED
            // But we also need to ensure API returns appropriate status - we return the result, not throw
            // For now, return the failed result
            return toResponse(ocrResult);
        } catch (Exception e) {
            String sanitizedError = sanitizeError(e.getMessage());
            ocrResult.setStatus(OcrStatus.FAILED);
            ocrResult.setErrorMessage(sanitizedError);
            ocrResult.setProcessingCompletedAt(Instant.now());
            ocrResultRepository.save(ocrResult);
            doc.setStatus(DocumentStatus.FAILED);
            documentRepository.save(doc);
            return toResponse(ocrResult);
        }
    }

    /**
     * Asynchronous request: validates, creates PENDING OCR, publishes event, returns 202.
     * Never performs expensive OCR synchronously. Worker ({@link #processOcr}) does actual work.
     * Transactional: if Kafka publish fails, the transaction rolls back so the document
     * does not remain stuck in a misleading PENDING state.
     */
    @Transactional
    public com.example.accounting.dto.ProcessingAcceptedResponse requestOcr(UUID userId, UUID documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!accessibleOrganizationIds(userId).contains(doc.getOrganization().getId())) {
            throw new DocumentNotFoundException(documentId);
        }
        requireOcrPermission(userId, doc.getOrganization().getId());

        String contentType = doc.getContentType() != null ? doc.getContentType().toLowerCase() : "";
        if (!contentType.equals("application/pdf") && !contentType.startsWith("image/")) {
            throw new OcrUnsupportedDocumentException("Unsupported document type for OCR: " + contentType);
        }
        if (!List.of("application/pdf", "image/jpeg", "image/png", "image/webp").contains(contentType)) {
            if (!contentType.equals("image/jpg") && !contentType.equals("image/jpeg") && !contentType.equals("image/png") && !contentType.equals("application/pdf") && !contentType.equals("image/webp")) {
                throw new OcrUnsupportedDocumentException("Unsupported content type: " + contentType);
            }
        }

        var existingOpt = ocrResultRepository.findByDocumentId(documentId);
        OcrResult ocrResult;
        if (existingOpt.isPresent()) {
            ocrResult = existingOpt.get();
            if (ocrResult.getStatus() == OcrStatus.PROCESSING) {
                throw new OcrAlreadyProcessingException("OCR already in progress for document: " + documentId);
            }
            // Reset for reprocessing: set to PENDING for async flow
            ocrResult.setStatus(OcrStatus.PENDING);
            ocrResult.setProcessingStartedAt(null);
            ocrResult.setProcessingCompletedAt(null);
            ocrResult.setErrorMessage(null);
            ocrResult.setExtractedText(null);
            ocrResult.setPageCount(null);
        } else {
            ocrResult = new OcrResult(doc, OcrStatus.PENDING);
            // created PENDING
        }
        // Document stays UPLOADED until worker picks up? But spec says HTTP PENDING, consumer PROCESSING.
        // We set document to PROCESSING to indicate queued, but could also keep UPLOADED.
        // To allow polling, set document PROCESSING now.
        doc.setStatus(DocumentStatus.PROCESSING);
        documentRepository.save(doc);
        ocrResult = ocrResultRepository.save(ocrResult);

        // Publish event (publisher no-ops when Kafka disabled, e.g. test profile —
        // worker is then driven explicitly via processOcr in tests).
        // Publish failure rolls back the transaction so the document is not stuck PENDING.
        var event = DocumentOcrRequestedEvent.create(documentId, doc.getOrganization().getId(), userId);
        try {
            eventPublisher.publishOcrRequested(event);
            log.info("OCR queued eventId={} documentId={} organizationId={}", event.eventId(), documentId, doc.getOrganization().getId());
        } catch (Exception e) {
            // Publish failed, rollback to avoid stuck PENDING
            // Since we are in transaction, throwing will rollback DB changes (PENDING)
            log.error("Failed to publish OCR event, rolling back documentId={}", documentId, e);
            throw new RuntimeException("Failed to queue OCR processing", e);
        }
        auditLogService.record(new AuditCommand(AuditAction.OCR_REQUESTED, AuditResourceType.OCR_RESULT,
                doc.getOrganization().getId(), userId, ocrResult.getId(), "OCR requested",
                null, auditLogService.toJson(AuditSnapshots.ocr(OcrStatus.PENDING.name(), documentId, null)),
                auditLogService.toJson(AuditSnapshots.metadata("API",
                        java.util.Map.of("documentId", documentId.toString(), "eventId", event.eventId().toString()))), true));

        return new com.example.accounting.dto.ProcessingAcceptedResponse(documentId, ocrResult.getId(), OcrStatus.PENDING.name(), "OCR processing has been queued");
    }

    /**
     * Worker method: called by Kafka consumer, performs actual OCR.
     * Idempotent: if already COMPLETED, ignore duplicate.
     */
    @Transactional
    public void processOcr(DocumentOcrRequestedEvent event) {
        if (event == null || event.documentId() == null || event.organizationId() == null) {
            throw new IllegalArgumentException("Invalid OCR event: missing identifiers");
        }
        log.info("OCR worker started eventId={} documentId={}", event.eventId(), event.documentId());

        Document doc = documentRepository.findById(event.documentId())
                .orElseThrow(() -> new IllegalArgumentException("Document not found for event: " + event.documentId()));

        // Tenant safety: verify organization matches
        if (!doc.getOrganization().getId().equals(event.organizationId())) {
            log.warn("Tenant mismatch for OCR event documentId={} eventOrg={} docOrg={}", event.documentId(), event.organizationId(), doc.getOrganization().getId());
            throw new IllegalArgumentException("Organization mismatch for document: " + event.documentId());
        }

        var existingOpt = ocrResultRepository.findByDocumentId(event.documentId());
        OcrResult ocrResult;
        if (existingOpt.isPresent()) {
            ocrResult = existingOpt.get();
            // Idempotency: if already COMPLETED, ignore duplicate
            if (ocrResult.getStatus() == OcrStatus.COMPLETED) {
                log.info("OCR already COMPLETED, ignoring duplicate event documentId={} eventId={}", event.documentId(), event.eventId());
                return;
            }
            if (ocrResult.getStatus() == OcrStatus.PROCESSING) {
                log.info("OCR already PROCESSING, duplicate event ignored documentId={}", event.documentId());
                return;
            }
            // For PENDING or FAILED, proceed to PROCESSING
            ocrResult.setStatus(OcrStatus.PROCESSING);
            ocrResult.setProcessingStartedAt(Instant.now());
            ocrResult.setProcessingCompletedAt(null);
            ocrResult.setErrorMessage(null);
            ocrResult.setExtractedText(null);
            ocrResult.setPageCount(null);
        } else {
            ocrResult = new OcrResult(doc, OcrStatus.PROCESSING);
            ocrResult.setProcessingStartedAt(Instant.now());
        }
        doc.setStatus(DocumentStatus.PROCESSING);
        documentRepository.save(doc);
        ocrResult = ocrResultRepository.save(ocrResult);

        try {
            processOcrInternal(doc, ocrResult);
            // On success, publish extraction event if applicable
            // Only when OCR succeeds, extraction should be triggered
            if (ocrResult.getStatus() == OcrStatus.COMPLETED) {
                log.info("OCR worker completed documentId={} eventId={}", event.documentId(), event.eventId());
                auditOcrWorkerResult(doc, ocrResult, event.eventId(), true);
                // Publish extraction automatically
                try {
                    var extractionEvent = com.example.accounting.kafka.event.DocumentExtractionRequestedEvent.create(
                            doc.getId(), doc.getOrganization().getId(), event.requestedByUserId());
                    eventPublisher.publishExtractionRequested(extractionEvent);
                    log.info("Auto-published extraction event after OCR success documentId={} extractionEventId={}", doc.getId(), extractionEvent.eventId());
                } catch (Exception ex) {
                    log.error("Failed to publish extraction event after OCR success documentId={}", doc.getId(), ex);
                    // Not fatal for OCR, but extraction will need manual trigger
                }
            }
        } catch (OcrException e) {
            String sanitized = sanitizeError(e.getMessage());
            ocrResult.setStatus(OcrStatus.FAILED);
            ocrResult.setErrorMessage(sanitized);
            ocrResult.setProcessingCompletedAt(Instant.now());
            ocrResultRepository.save(ocrResult);
            doc.setStatus(DocumentStatus.FAILED);
            documentRepository.save(doc);
            log.info("OCR worker failed documentId={} reason={}", event.documentId(), sanitized);
            auditOcrWorkerResult(doc, ocrResult, event.eventId(), false);
        } catch (Exception e) {
            String sanitized = sanitizeError(e.getMessage());
            ocrResult.setStatus(OcrStatus.FAILED);
            ocrResult.setErrorMessage(sanitized);
            ocrResult.setProcessingCompletedAt(Instant.now());
            ocrResultRepository.save(ocrResult);
            doc.setStatus(DocumentStatus.FAILED);
            documentRepository.save(doc);
            log.info("OCR worker failed documentId={} reason={}", event.documentId(), sanitized);
            auditOcrWorkerResult(doc, ocrResult, event.eventId(), false);
            // For transient failures, throw to trigger retry
            if (isTransient(e)) {
                throw new RuntimeException("Transient OCR failure", e);
            }
        }
    }

    /**
     * Worker audit: joins the worker transaction so a rolled-back OCR does not
     * leave a false success record. Early-return duplicates (already COMPLETED
     * or PROCESSING) never reach this method, so retries cannot produce
     * unbounded identical rows. Only safe summary fields are stored — never
     * extracted text.
     */
    private void auditOcrWorkerResult(Document doc, OcrResult ocrResult, UUID eventId, boolean completed) {
        try {
            auditLogService.record(new AuditCommand(
                    completed ? AuditAction.OCR_COMPLETED : AuditAction.OCR_FAILED,
                    AuditResourceType.OCR_RESULT,
                    doc.getOrganization().getId(), null, ocrResult.getId(),
                    completed ? "OCR completed" : "OCR failed",
                    auditLogService.toJson(AuditSnapshots.ocr(OcrStatus.PROCESSING.name(), doc.getId(), null)),
                    auditLogService.toJson(AuditSnapshots.ocr(
                            completed ? OcrStatus.COMPLETED.name() : OcrStatus.FAILED.name(),
                            doc.getId(), ocrResult.getPageCount())),
                    auditLogService.toJson(AuditSnapshots.metadata("KAFKA_WORKER",
                            java.util.Map.of("documentId", doc.getId().toString(),
                                    "eventId", eventId != null ? eventId.toString() : "unknown",
                                    "processingType", "OCR"))),
                    completed));
        } catch (Exception ex) {
            log.warn("Failed to record OCR worker audit documentId={}", doc.getId());
        }
    }

    private void processOcrInternal(Document doc, OcrResult ocrResult) throws Exception {
        Path tempFile = null;
        try {
            String storageKey = doc.getStorageKey();
            Path tempDir = Files.createTempDirectory("ocr-");
            String ext = getExtension(doc.getOriginalFileName(), doc.getContentType());
            tempFile = Files.createTempFile(tempDir, "doc-", "." + ext);
            try (InputStream is = storageService.retrieve(storageKey)) {
                Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            OcrData data = ocrProvider.extractText(tempFile, doc.getContentType(), doc.getOriginalFileName());
            ocrResult.setExtractedText(data.extractedText());
            ocrResult.setPageCount(data.pageCount());
            ocrResult.setStatus(OcrStatus.COMPLETED);
            ocrResult.setProcessingCompletedAt(Instant.now());
            ocrResult.setErrorMessage(null);
            doc.setStatus(DocumentStatus.PROCESSED);
            documentRepository.save(doc);
            ocrResultRepository.save(ocrResult);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                    Path parent = tempFile.getParent();
                    if (parent != null && Files.isDirectory(parent)) {
                        try {
                            if (Files.list(parent).findAny().isEmpty()) {
                                Files.deleteIfExists(parent);
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private boolean isTransient(Exception e) {
        // Simple heuristic: transient if not IllegalArgumentException and not OcrException
        return !(e instanceof IllegalArgumentException) && !(e instanceof OcrException);
    }

    @Transactional(readOnly = true)
    public OcrResultResponse getOcrResult(UUID userId, UUID documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!accessibleOrganizationIds(userId).contains(doc.getOrganization().getId())) {
            throw new DocumentNotFoundException(documentId);
        }
        // Any member can read
        requireReadPermission(userId, doc.getOrganization().getId());

        OcrResult result = ocrResultRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new com.example.accounting.exception.OcrResultNotFoundException(documentId));
        return toResponse(result);
    }

    private String getExtension(String filename, String contentType) {
        if (filename != null && filename.contains(".")) {
            String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
            if (ext.length() <= 10) return ext;
        }
        if ("application/pdf".equals(contentType)) return "pdf";
        if ("image/jpeg".equals(contentType) || "image/jpg".equals(contentType)) return "jpg";
        if ("image/png".equals(contentType)) return "png";
        if ("image/webp".equals(contentType)) return "webp";
        return "bin";
    }

    private String sanitizeError(String message) {
        if (message == null) return "OCR processing failed";
        // Remove filesystem paths, keep bounded
        String sanitized = message.replaceAll("(/[\\w/.-]+)+", "[path]");
        sanitized = sanitized.replaceAll("\\\\[\\w\\\\.-]+", "[path]");
        if (sanitized.length() > 1000) {
            sanitized = sanitized.substring(0, 1000);
        }
        // Do not expose secrets or stack traces
        sanitized = sanitized.replaceAll("(?i)secret.*", "[redacted]");
        return sanitized;
    }

    private OcrResultResponse toResponse(OcrResult r) {
        return new OcrResultResponse(
                r.getId(),
                r.getDocument().getId(),
                r.getStatus(),
                r.getExtractedText(),
                r.getPageCount(),
                r.getProcessingStartedAt(),
                r.getProcessingCompletedAt(),
                r.getErrorMessage(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
