package com.example.accounting.service;

import com.example.accounting.audit.AuditAction;
import com.example.accounting.audit.AuditCommand;
import com.example.accounting.audit.AuditLogService;
import com.example.accounting.audit.AuditResourceType;
import com.example.accounting.audit.AuditSnapshots;
import com.example.accounting.ai.AiDocumentExtractor;
import com.example.accounting.dto.DocumentExtractionResponse;
import com.example.accounting.dto.StructuredInvoiceExtraction;
import com.example.accounting.entity.*;
import com.example.accounting.exception.DocumentNotFoundException;
import com.example.accounting.exception.OcrResultNotFoundException;
import com.example.accounting.kafka.DocumentProcessingEventPublisher;
import com.example.accounting.kafka.KafkaProperties;
import com.example.accounting.kafka.event.DocumentExtractionRequestedEvent;
import com.example.accounting.repository.DocumentExtractionRepository;
import com.example.accounting.repository.DocumentRepository;
import com.example.accounting.repository.DocumentReviewRepository;
import com.example.accounting.repository.OcrResultRepository;
import com.example.accounting.repository.OrganizationMembershipRepository;
import com.example.accounting.security.OrganizationAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DocumentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractionService.class);

    private final DocumentRepository documentRepository;
    private final OcrResultRepository ocrResultRepository;
    private final DocumentExtractionRepository extractionRepository;
    private final DocumentReviewRepository reviewRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationAccessService organizationAccessService;
    private final AiDocumentExtractor aiExtractor;
    private final ObjectMapper objectMapper;
    private final DocumentProcessingEventPublisher eventPublisher;
    private final KafkaProperties kafkaProperties;
    private final AuditLogService auditLogService;

    public DocumentExtractionService(DocumentRepository documentRepository,
                                     OcrResultRepository ocrResultRepository,
                                     DocumentExtractionRepository extractionRepository,
                                     DocumentReviewRepository reviewRepository,
                                     OrganizationMembershipRepository membershipRepository,
                                     OrganizationAccessService organizationAccessService,
                                     AiDocumentExtractor aiExtractor,
                                     ObjectMapper objectMapper,
                                     DocumentProcessingEventPublisher eventPublisher,
                                     KafkaProperties kafkaProperties,
                                     AuditLogService auditLogService) {
        this.documentRepository = documentRepository;
        this.ocrResultRepository = ocrResultRepository;
        this.extractionRepository = extractionRepository;
        this.reviewRepository = reviewRepository;
        this.membershipRepository = membershipRepository;
        this.organizationAccessService = organizationAccessService;
        this.aiExtractor = aiExtractor;
        this.objectMapper = objectMapper;
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

    private void requireWritePermission(UUID userId, UUID orgId) {
        var roleOpt = organizationAccessService.userRole(userId, orgId);
        var role = roleOpt.orElseThrow(() -> new AccessDeniedException("No membership"));
        if (role == MembershipRole.VIEWER || role == MembershipRole.EMPLOYEE) {
            throw new AccessDeniedException("Role " + role + " cannot start extraction");
        }
    }

    @Transactional
    public DocumentExtractionResponse startExtraction(UUID userId, UUID documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!accessibleOrganizationIds(userId).contains(doc.getOrganization().getId())) {
            throw new DocumentNotFoundException(documentId);
        }
        requireWritePermission(userId, doc.getOrganization().getId());

        // Check OCR completion
        OcrResult ocrResult = ocrResultRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new com.example.accounting.exception.OcrNotCompletedException(documentId));
        if (ocrResult.getStatus() != OcrStatus.COMPLETED) {
            throw new com.example.accounting.exception.OcrNotCompletedException(documentId);
        }
        if (ocrResult.getExtractedText() == null || ocrResult.getExtractedText().trim().isEmpty()) {
            throw new com.example.accounting.exception.OcrNotCompletedException(documentId);
        }

        // Check existing extraction
        var existingOpt = extractionRepository.findByDocumentId(documentId);
        DocumentExtraction extraction;
        if (existingOpt.isPresent()) {
            extraction = existingOpt.get();
            if (extraction.getStatus() == DocumentExtractionStatus.PROCESSING) {
                throw new com.example.accounting.exception.DocumentExtractionAlreadyProcessingException(documentId);
            }
            // Reset for reprocessing - also reset review state so old approval does not remain valid for new AI result
            // The review is tied to the same extraction row (UNIQUE extraction_id), so we must clear it
            reviewRepository.findByExtractionId(extraction.getId()).ifPresent(review -> {
                reviewRepository.delete(review);
                // flush to ensure constraint cleared before new extraction completes
                reviewRepository.flush();
            });
            extraction.setStatus(DocumentExtractionStatus.PROCESSING);
            extraction.setProcessingStartedAt(Instant.now());
            extraction.setProcessingCompletedAt(null);
            extraction.setErrorMessage(null);
            extraction.setRawResponse(null);
            extraction.setStructuredData(null);
            extraction.setConfidence(null);
        } else {
            extraction = new DocumentExtraction(doc, doc.getOrganization(), doc.getDocumentType());
            extraction.setStatus(DocumentExtractionStatus.PROCESSING);
            extraction.setProcessingStartedAt(Instant.now());
            // id will be generated
        }
        extraction = extractionRepository.save(extraction);

        try {
            String ocrText = ocrResult.getExtractedText();
            DocumentType docType = doc.getDocumentType();

            AiDocumentExtractor.ExtractionResult result = aiExtractor.extract(ocrText, docType);

            // Validate structured data
            StructuredInvoiceExtraction structured = result.structuredData();
            validateStructuredData(structured);

            // Validate confidence if provided
            if (result.confidence() != null) {
                validateConfidence(result.confidence());
            }

            String structuredJson;
            try {
                structuredJson = objectMapper.writeValueAsString(structured);
            } catch (com.fasterxml.jackson.core.JsonProcessingException je) {
                throw new IllegalArgumentException("Failed to serialize structured data: " + je.getMessage(), je);
            }
            // Validate that structuredJson is valid JSON
            try {
                objectMapper.readTree(structuredJson);
            } catch (Exception je) {
                throw new IllegalArgumentException("Invalid structured JSON: " + je.getMessage(), je);
            }
            // Ensure rawResponse doesn't contain secrets
            String sanitizedRaw = sanitizeRawResponse(result.rawResponse());

            extraction.setModel(result.model());
            extraction.setPromptVersion(result.promptVersion());
            extraction.setRawResponse(sanitizedRaw);
            extraction.setStructuredData(structuredJson);
            extraction.setConfidence(result.confidence());
            extraction.setStatus(DocumentExtractionStatus.COMPLETED);
            extraction.setProcessingCompletedAt(Instant.now());
            extraction.setErrorMessage(null);
            extraction = extractionRepository.save(extraction);
            return toResponse(extraction);

        } catch (Exception e) {
            String sanitizedError = sanitizeError(e.getMessage());
            extraction.setStatus(DocumentExtractionStatus.FAILED);
            extraction.setErrorMessage(sanitizedError);
            extraction.setProcessingCompletedAt(Instant.now());
            // Keep model/prompt version if available from partial result?
            extractionRepository.save(extraction);
            // Return the failed result instead of throwing, to allow GET to retrieve it
            // But we also need to ensure API returns appropriate status - we will return the FAILED response
            // However, if AI provider failed, we should not throw 500 but return FAILED result
            // For unexpected errors, we throw as 500
            if (e instanceof com.example.accounting.exception.DocumentExtractionAlreadyProcessingException) {
                throw e;
            }
            // For validation failures, mark FAILED and return
            if (e instanceof IllegalArgumentException) {
                extraction.setErrorMessage(sanitizeError(e.getMessage()));
                extractionRepository.save(extraction);
                return toResponse(extraction);
            }
            // For other AI failures, return FAILED
            return toResponse(extraction);
        }
    }

    /**
     * Asynchronous request: validates OCR COMPLETED, creates PENDING extraction, publishes event, returns 202.
     * Never calls the external LLM from the HTTP request. Worker ({@link #processExtraction}) does actual work.
     * Publish failure rolls back so extraction is not stuck in a misleading state.
     */
    @Transactional
    public com.example.accounting.dto.ProcessingAcceptedResponse requestExtraction(UUID userId, UUID documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!accessibleOrganizationIds(userId).contains(doc.getOrganization().getId())) {
            throw new DocumentNotFoundException(documentId);
        }
        requireWritePermission(userId, doc.getOrganization().getId());

        OcrResult ocrResult = ocrResultRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new com.example.accounting.exception.OcrNotCompletedException(documentId));
        if (ocrResult.getStatus() != OcrStatus.COMPLETED) {
            throw new com.example.accounting.exception.OcrNotCompletedException(documentId);
        }
        if (ocrResult.getExtractedText() == null || ocrResult.getExtractedText().trim().isEmpty()) {
            throw new com.example.accounting.exception.OcrNotCompletedException(documentId);
        }

        var existingOpt = extractionRepository.findByDocumentId(documentId);
        DocumentExtraction extraction;
        if (existingOpt.isPresent()) {
            extraction = existingOpt.get();
            if (extraction.getStatus() == DocumentExtractionStatus.PROCESSING) {
                throw new com.example.accounting.exception.DocumentExtractionAlreadyProcessingException(documentId);
            }
            reviewRepository.findByExtractionId(extraction.getId()).ifPresent(review -> {
                reviewRepository.delete(review);
                reviewRepository.flush();
            });
            extraction.setStatus(DocumentExtractionStatus.PENDING);
            extraction.setProcessingStartedAt(null);
            extraction.setProcessingCompletedAt(null);
            extraction.setErrorMessage(null);
            extraction.setRawResponse(null);
            extraction.setStructuredData(null);
            extraction.setConfidence(null);
        } else {
            extraction = new DocumentExtraction(doc, doc.getOrganization(), doc.getDocumentType());
            extraction.setStatus(DocumentExtractionStatus.PENDING);
        }
        extraction = extractionRepository.save(extraction);

        // Publish extraction event (publisher no-ops when Kafka disabled, e.g. test profile —
        // worker is then driven explicitly via processExtraction in tests).
        var event = DocumentExtractionRequestedEvent.create(documentId, doc.getOrganization().getId(), userId);
        try {
            eventPublisher.publishExtractionRequested(event);
            log.info("Extraction queued eventId={} documentId={}", event.eventId(), documentId);
        } catch (Exception e) {
            log.error("Failed to publish extraction event documentId={}", documentId, e);
            throw new RuntimeException("Failed to queue extraction", e);
        }
        auditLogService.record(new AuditCommand(AuditAction.DOCUMENT_EXTRACTION_REQUESTED,
                AuditResourceType.DOCUMENT_EXTRACTION,
                doc.getOrganization().getId(), userId, extraction.getId(), "AI extraction requested",
                null, auditLogService.toJson(AuditSnapshots.extraction(
                        DocumentExtractionStatus.PENDING.name(), documentId,
                        doc.getDocumentType() != null ? doc.getDocumentType().name() : null, null, null)),
                auditLogService.toJson(AuditSnapshots.metadata("API",
                        java.util.Map.of("documentId", documentId.toString(),
                                "eventId", event.eventId().toString(), "processingType", "AI_EXTRACTION"))), true));
        return new com.example.accounting.dto.ProcessingAcceptedResponse(documentId, extraction.getId(), DocumentExtractionStatus.PENDING.name(), "AI extraction has been queued");
    }

    /**
     * Worker method for Kafka consumer.
     */
    @Transactional
    public void processExtraction(DocumentExtractionRequestedEvent event) {
        if (event == null || event.documentId() == null || event.organizationId() == null) {
            throw new IllegalArgumentException("Invalid extraction event");
        }
        log.info("Extraction worker started eventId={} documentId={}", event.eventId(), event.documentId());

        Document doc = documentRepository.findById(event.documentId())
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + event.documentId()));
        if (!doc.getOrganization().getId().equals(event.organizationId())) {
            log.warn("Tenant mismatch extraction documentId={} eventOrg={} docOrg={}", event.documentId(), event.organizationId(), doc.getOrganization().getId());
            throw new IllegalArgumentException("Organization mismatch");
        }

        OcrResult ocrResult = ocrResultRepository.findByDocumentId(event.documentId())
                .orElseThrow(() -> new IllegalArgumentException("OCR not found for document: " + event.documentId()));
        if (ocrResult.getStatus() != OcrStatus.COMPLETED) {
            throw new IllegalArgumentException("OCR not completed for document: " + event.documentId());
        }
        if (ocrResult.getExtractedText() == null || ocrResult.getExtractedText().trim().isEmpty()) {
            throw new IllegalArgumentException("OCR text empty for document: " + event.documentId());
        }

        var existingOpt = extractionRepository.findByDocumentId(event.documentId());
        DocumentExtraction extraction;
        if (existingOpt.isPresent()) {
            extraction = existingOpt.get();
            if (extraction.getStatus() == DocumentExtractionStatus.COMPLETED) {
                log.info("Extraction already COMPLETED, ignoring duplicate event documentId={}", event.documentId());
                return;
            }
            if (extraction.getStatus() == DocumentExtractionStatus.PROCESSING) {
                log.info("Extraction already PROCESSING, ignoring duplicate documentId={}", event.documentId());
                return;
            }
            // PENDING or FAILED -> move to PROCESSING
            reviewRepository.findByExtractionId(extraction.getId()).ifPresent(review -> {
                reviewRepository.delete(review);
                reviewRepository.flush();
            });
            extraction.setStatus(DocumentExtractionStatus.PROCESSING);
            extraction.setProcessingStartedAt(Instant.now());
            extraction.setProcessingCompletedAt(null);
            extraction.setErrorMessage(null);
            extraction.setRawResponse(null);
            extraction.setStructuredData(null);
            extraction.setConfidence(null);
        } else {
            extraction = new DocumentExtraction(doc, doc.getOrganization(), doc.getDocumentType());
            extraction.setStatus(DocumentExtractionStatus.PROCESSING);
            extraction.setProcessingStartedAt(Instant.now());
        }
        extraction = extractionRepository.save(extraction);

        try {
            String ocrText = ocrResult.getExtractedText();
            DocumentType docType = doc.getDocumentType();
            AiDocumentExtractor.ExtractionResult result = aiExtractor.extract(ocrText, docType);
            StructuredInvoiceExtraction structured = result.structuredData();
            validateStructuredData(structured);
            if (result.confidence() != null) {
                validateConfidence(result.confidence());
            }
            String structuredJson = objectMapper.writeValueAsString(structured);
            objectMapper.readTree(structuredJson);
            String sanitizedRaw = sanitizeRawResponse(result.rawResponse());
            extraction.setModel(result.model());
            extraction.setPromptVersion(result.promptVersion());
            extraction.setRawResponse(sanitizedRaw);
            extraction.setStructuredData(structuredJson);
            extraction.setConfidence(result.confidence());
            extraction.setStatus(DocumentExtractionStatus.COMPLETED);
            extraction.setProcessingCompletedAt(Instant.now());
            extraction.setErrorMessage(null);
            extractionRepository.save(extraction);
            log.info("Extraction worker completed documentId={} eventId={}", event.documentId(), event.eventId());
            auditExtractionWorkerResult(doc, extraction, event.eventId(), true);
        } catch (IllegalArgumentException iae) {
            String sanitized = sanitizeError(iae.getMessage());
            extraction.setStatus(DocumentExtractionStatus.FAILED);
            extraction.setErrorMessage(sanitized);
            extraction.setProcessingCompletedAt(Instant.now());
            extractionRepository.save(extraction);
            log.info("Extraction worker validation failed documentId={} reason={}", event.documentId(), sanitized);
            auditExtractionWorkerResult(doc, extraction, event.eventId(), false);
            // Not retryable for validation errors
            throw iae;
        } catch (Exception e) {
            String sanitized = sanitizeError(e.getMessage());
            extraction.setStatus(DocumentExtractionStatus.FAILED);
            extraction.setErrorMessage(sanitized);
            extraction.setProcessingCompletedAt(Instant.now());
            extractionRepository.save(extraction);
            log.info("Extraction worker failed documentId={} reason={}", event.documentId(), sanitized);
            auditExtractionWorkerResult(doc, extraction, event.eventId(), false);
            // Transient if not validation
            throw new RuntimeException("Extraction failed", e);
        }
    }

    /**
     * Worker audit joining the worker transaction. Duplicates that hit the
     * early COMPLETED/PROCESSING return never reach this method. Metadata
     * carries only extraction type, model and status — never prompts,
     * OCR text or raw model responses.
     */
    private void auditExtractionWorkerResult(Document doc, DocumentExtraction extraction,
                                             UUID eventId, boolean completed) {
        try {
            auditLogService.record(new AuditCommand(
                    completed ? AuditAction.DOCUMENT_EXTRACTION_COMPLETED
                            : AuditAction.DOCUMENT_EXTRACTION_FAILED,
                    AuditResourceType.DOCUMENT_EXTRACTION,
                    doc.getOrganization().getId(), null, extraction.getId(),
                    completed ? "AI extraction completed" : "AI extraction failed",
                    auditLogService.toJson(AuditSnapshots.extraction(
                            DocumentExtractionStatus.PROCESSING.name(), doc.getId(),
                            extraction.getExtractionType() != null
                                    ? extraction.getExtractionType().name() : null,
                            extraction.getModel(), extraction.getPromptVersion())),
                    auditLogService.toJson(AuditSnapshots.extraction(
                            completed ? DocumentExtractionStatus.COMPLETED.name()
                                    : DocumentExtractionStatus.FAILED.name(),
                            doc.getId(),
                            extraction.getExtractionType() != null
                                    ? extraction.getExtractionType().name() : null,
                            extraction.getModel(), extraction.getPromptVersion())),
                    auditLogService.toJson(AuditSnapshots.metadata("KAFKA_WORKER",
                            java.util.Map.of("documentId", doc.getId().toString(),
                                    "eventId", eventId != null ? eventId.toString() : "unknown",
                                    "processingType", "AI_EXTRACTION",
                                    "extractionType", extraction.getExtractionType() != null
                                            ? extraction.getExtractionType().name() : "unknown",
                                    "model", extraction.getModel() != null ? extraction.getModel() : "unknown",
                                    "confidence", extraction.getConfidence() != null
                                            ? extraction.getConfidence().toString() : "unknown"))),
                    completed));
        } catch (Exception ex) {
            log.warn("Failed to record extraction worker audit documentId={}", doc.getId());
        }
    }

    @Transactional(readOnly = true)
    public DocumentExtractionResponse getExtraction(UUID userId, UUID documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!accessibleOrganizationIds(userId).contains(doc.getOrganization().getId())) {
            throw new DocumentNotFoundException(documentId);
        }
        requireReadPermission(userId, doc.getOrganization().getId());
        DocumentExtraction extraction = extractionRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new com.example.accounting.exception.DocumentExtractionNotFoundException(documentId));
        return toResponse(extraction);
    }

    private void validateStructuredData(StructuredInvoiceExtraction data) {
        if (data == null) {
            throw new IllegalArgumentException("Structured data is null");
        }
        // Validate top-level monetary values cannot be negative
        if (data.subtotal() != null && data.subtotal().signum() < 0) {
            throw new IllegalArgumentException("subtotal cannot be negative");
        }
        if (data.taxAmount() != null && data.taxAmount().signum() < 0) {
            throw new IllegalArgumentException("taxAmount cannot be negative");
        }
        if (data.discountAmount() != null && data.discountAmount().signum() < 0) {
            throw new IllegalArgumentException("discountAmount cannot be negative");
        }
        if (data.totalAmount() != null && data.totalAmount().signum() < 0) {
            throw new IllegalArgumentException("totalAmount cannot be negative");
        }
        // Validate line items
        if (data.lineItems() != null) {
            for (var line : data.lineItems()) {
                if (line.quantity() != null && line.quantity().signum() < 0) {
                    throw new IllegalArgumentException("quantity cannot be negative");
                }
                if (line.unitPrice() != null && line.unitPrice().signum() < 0) {
                    throw new IllegalArgumentException("unitPrice cannot be negative");
                }
                if (line.taxRate() != null && (line.taxRate().signum() < 0 || line.taxRate().compareTo(new BigDecimal("100")) > 0)) {
                    throw new IllegalArgumentException("taxRate must be between 0 and 100");
                }
                if (line.taxRate() != null && line.taxRate().signum() < 0) {
                    throw new IllegalArgumentException("taxRate cannot be negative");
                }
                if (line.taxAmount() != null && line.taxAmount().signum() < 0) {
                    throw new IllegalArgumentException("line taxAmount cannot be negative");
                }
                if (line.discountAmount() != null && line.discountAmount().signum() < 0) {
                    throw new IllegalArgumentException("discountAmount cannot be negative");
                }
                if (line.lineTotal() != null && line.lineTotal().signum() < 0) {
                    throw new IllegalArgumentException("lineTotal cannot be negative");
                }
                // Basic string length guard for description
                if (line.description() != null && line.description().length() > 500) {
                    throw new IllegalArgumentException("line description too long");
                }
            }
        }
        // Currency format check - should be 3 uppercase letters if present, max 10 kept for flexibility
        if (data.currency() != null) {
            if (data.currency().length() > 10) {
                throw new IllegalArgumentException("currency format invalid");
            }
            if (!data.currency().matches("^[A-Z]{3}$") && data.currency().length() == 3) {
                // allow if 3 chars but not uppercase -> invalid
                throw new IllegalArgumentException("currency format invalid: must be 3 uppercase letters");
            }
            // If longer, just ensure it contains only letters
            if (data.currency().length() != 3 && !data.currency().matches("^[A-Za-z]+$")) {
                throw new IllegalArgumentException("currency format invalid");
            }
        }
        // Dates must be valid ISO dates (YYYY-MM-DD) if present
        if (data.invoiceDate() != null && !data.invoiceDate().isBlank()) {
            validateDate(data.invoiceDate(), "invoiceDate");
        }
        if (data.dueDate() != null && !data.dueDate().isBlank()) {
            validateDate(data.dueDate(), "dueDate");
        }
        // Invoice number preservation - no validation besides length
        if (data.invoiceNumber() != null && data.invoiceNumber().length() > 100) {
            throw new IllegalArgumentException("invoiceNumber too long");
        }
    }

    private void validateDate(String dateStr, String fieldName) {
        try {
            java.time.LocalDate.parse(dateStr);
        } catch (Exception e) {
            // Try also ISO instant parsing fallback, but require YYYY-MM-DD
            throw new IllegalArgumentException(fieldName + " must be valid date (YYYY-MM-DD): " + dateStr);
        }
    }

    private void validateConfidence(BigDecimal confidence) {
        if (confidence == null) return;
        if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        // Scale check: DECIMAL(5,4) allows up to 4 decimal places, ensure not too precise
        if (confidence.scale() > 4) {
            throw new IllegalArgumentException("confidence scale must be <= 4");
        }
    }

    private String sanitizeError(String message) {
        if (message == null) return "Extraction failed";
        String sanitized = message.replaceAll("(?i)api[_-]?key[^\\s]*", "[redacted]");
        sanitized = sanitized.replaceAll("(?i)secret[^\\s]*", "[redacted]");
        sanitized = sanitized.replaceAll("(/[\\w/.-]+)+", "[path]");
        if (sanitized.length() > 1000) sanitized = sanitized.substring(0, 1000);
        return sanitized;
    }

    private String sanitizeRawResponse(String raw) {
        if (raw == null) return null;
        // Remove any potential secrets (simple)
        String sanitized = raw.replaceAll("(?i)api[_-]?key[^\\s]*", "[redacted]");
        if (sanitized.length() > 50000) {
            sanitized = sanitized.substring(0, 50000);
        }
        return sanitized;
    }

    private DocumentExtractionResponse toResponse(DocumentExtraction e) {
        Object structuredData = null;
        if (e.getStructuredData() != null) {
            try {
                structuredData = objectMapper.readValue(e.getStructuredData(), StructuredInvoiceExtraction.class);
            } catch (Exception ex) {
                // fallback to raw string
                try {
                    structuredData = objectMapper.readValue(e.getStructuredData(), Object.class);
                } catch (Exception ignored) {
                    structuredData = null;
                }
            }
        }
        return new DocumentExtractionResponse(
                e.getId(),
                e.getDocument().getId(),
                e.getExtractionType(),
                e.getStatus(),
                e.getModel(),
                e.getPromptVersion(),
                structuredData,
                e.getConfidence(),
                e.getProcessingStartedAt(),
                e.getProcessingCompletedAt(),
                e.getErrorMessage(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }
}
