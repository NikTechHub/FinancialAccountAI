package com.example.accounting.kafka;

import com.example.accounting.dto.RegisterRequest;
import com.example.accounting.entity.DocumentExtractionStatus;
import com.example.accounting.entity.DocumentStatus;
import com.example.accounting.entity.OcrStatus;
import com.example.accounting.kafka.event.DocumentExtractionRequestedEvent;
import com.example.accounting.kafka.event.DocumentOcrRequestedEvent;
import com.example.accounting.kafka.event.DocumentProcessingDlqEvent;
import com.example.accounting.repository.*;
import com.example.accounting.service.DocumentExtractionService;
import com.example.accounting.service.OcrService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Kafka async pipeline integration tests — no broker required.
 * Uses test profile (kafka disabled, Fake providers) and drives workers explicitly
 * to simulate Kafka delivery. Publisher is mocked to verify publish interactions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KafkaDocumentProcessingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OcrService ocrService;
    @Autowired
    private DocumentExtractionService extractionService;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private OcrResultRepository ocrResultRepository;
    @Autowired
    private DocumentExtractionRepository extractionRepository;
    @Autowired
    private DocumentReviewRepository reviewRepository;
    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private ExpenseRepository expenseRepository;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private KafkaProperties kafkaProperties;

    @MockBean
    private DocumentProcessingEventPublisher eventPublisher;

    private String ownerToken;
    private String ownerUserId;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        // publisher mock must not throw
        doNothing().when(eventPublisher).publishOcrRequested(any());
        doNothing().when(eventPublisher).publishExtractionRequested(any());

        String suffix = "kafka-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Kafka", "Owner",
                "kafka.owner." + suffix + "@example.com",
                "SecurePass123",
                "KafkaOrg-" + suffix,
                "Legal Kafka " + suffix,
                "PRIVATE_LIMITED",
                "India", "INR", "Asia/Kolkata"
        );
        String resp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        ownerToken = objectMapper.readTree(resp).get("accessToken").asText();
        ownerUserId = objectMapper.readTree(resp).get("user").get("id").asText();
        orgId = objectMapper.readTree(resp).get("organization").get("id").asText();
    }

    private String upload(String filename, String content, String docType) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "application/pdf", content.getBytes());
        String resp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", docType)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("id").asText();
    }

    // ---- Producer / request ----

    @Test
    void ocrRequestShouldQueuePendingAndPublishEvent() throws Exception {
        String docId = upload("invoice.pdf", "%PDF-1.4 INV-001", "INVOICE");
        String resp = mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.documentId").value(docId))
                .andExpect(jsonPath("$.message").value("OCR processing has been queued"))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(resp).get("operationId").asText()).isNotEmpty();
        verify(eventPublisher).publishOcrRequested(any(DocumentOcrRequestedEvent.class));

        // GET shows PENDING before worker
        mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        // document PROCESSING while queued
        var doc = documentRepository.findById(UUID.fromString(docId)).orElseThrow();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
    }

    @Test
    void extractionRequestShouldQueuePendingAndPublishEvent() throws Exception {
        String docId = upload("inv.pdf", "content", "INVOICE");
        ocrService.processOcr(DocumentOcrRequestedEvent.create(
                UUID.fromString(docId), UUID.fromString(orgId), UUID.fromString(ownerUserId)));
        clearInvocations(eventPublisher);
        // queue extraction
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", docId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.message").value("AI extraction has been queued"));
        verify(eventPublisher).publishExtractionRequested(any(DocumentExtractionRequestedEvent.class));
        mockMvc.perform(get("/api/v1/documents/{documentId}/extraction", docId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void extractionRequestShouldRequireOcrCompleted() throws Exception {
        String docId = upload("noocr.pdf", "content", "INVOICE");
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", docId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
        verify(eventPublisher, never()).publishExtractionRequested(any());
    }

    // ---- Worker: OCR ----

    @Test
    void ocrWorkerShouldCompleteAndAutoPublishExtraction() {
        String docId = null;
        try {
            docId = upload("auto.pdf", "content", "INVOICE");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // queue (publishes OCR)
        ocrService.requestOcr(UUID.fromString(ownerUserId), UUID.fromString(docId));
        verify(eventPublisher).publishOcrRequested(any());
        // worker
        var event = DocumentOcrRequestedEvent.create(
                UUID.fromString(docId), UUID.fromString(orgId), UUID.fromString(ownerUserId));
        ocrService.processOcr(event);
        var ocr = ocrResultRepository.findByDocumentId(UUID.fromString(docId)).orElseThrow();
        assertThat(ocr.getStatus()).isEqualTo(OcrStatus.COMPLETED);
        assertThat(ocr.getExtractedText()).isNotEmpty();
        var doc = documentRepository.findById(UUID.fromString(docId)).orElseThrow();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PROCESSED);
        // auto extraction trigger on success
        verify(eventPublisher).publishExtractionRequested(any(DocumentExtractionRequestedEvent.class));
    }

    @Test
    void ocrWorkerFailureShouldMarkFailedAndNotPublishExtraction() throws Exception {
        String docId = upload("corrupt.pdf", "corrupt", "OTHER");
        ocrService.requestOcr(UUID.fromString(ownerUserId), UUID.fromString(docId));
        clearInvocations(eventPublisher);
        ocrService.processOcr(DocumentOcrRequestedEvent.create(
                UUID.fromString(docId), UUID.fromString(orgId), UUID.fromString(ownerUserId)));
        var ocr = ocrResultRepository.findByDocumentId(UUID.fromString(docId)).orElseThrow();
        assertThat(ocr.getStatus()).isEqualTo(OcrStatus.FAILED);
        var doc = documentRepository.findById(UUID.fromString(docId)).orElseThrow();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
        // no extraction on OCR failure
        verify(eventPublisher, never()).publishExtractionRequested(any());
    }

    @Test
    void ocrWorkerShouldBeIdempotent_duplicateCompletedIgnored() throws Exception {
        String docId = upload("idem.pdf", "content", "OTHER");
        var event = DocumentOcrRequestedEvent.create(
                UUID.fromString(docId), UUID.fromString(orgId), UUID.fromString(ownerUserId));
        ocrService.requestOcr(UUID.fromString(ownerUserId), UUID.fromString(docId));
        ocrService.processOcr(event);
        var first = ocrResultRepository.findByDocumentId(UUID.fromString(docId)).orElseThrow();
        assertThat(first.getStatus()).isEqualTo(OcrStatus.COMPLETED);
        long countBefore = ocrResultRepository.count();
        // duplicate delivery
        ocrService.processOcr(event);
        assertThat(ocrResultRepository.count()).isEqualTo(countBefore);
        var second = ocrResultRepository.findByDocumentId(UUID.fromString(docId)).orElseThrow();
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo(OcrStatus.COMPLETED);
    }

    @Test
    void ocrWorkerShouldRejectInvalidAndTenantMismatch() throws Exception {
        // invalid: null ids
        assertThatThrownBy(() -> ocrService.processOcr(
                new DocumentOcrRequestedEvent(UUID.randomUUID(), null, null, null, 1, java.time.Instant.now())))
                .isInstanceOf(IllegalArgumentException.class);
        // tenant mismatch
        String docId = upload("tenant.pdf", "content", "OTHER");
        ocrService.requestOcr(UUID.fromString(ownerUserId), UUID.fromString(docId));
        var badOrg = DocumentOcrRequestedEvent.create(
                UUID.fromString(docId), UUID.randomUUID(), UUID.fromString(ownerUserId));
        assertThatThrownBy(() -> ocrService.processOcr(badOrg))
                .isInstanceOf(IllegalArgumentException.class);
        // missing document
        var missing = DocumentOcrRequestedEvent.create(
                UUID.randomUUID(), UUID.fromString(orgId), UUID.fromString(ownerUserId));
        assertThatThrownBy(() -> ocrService.processOcr(missing))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Worker: extraction ----

    @Test
    void extractionWorkerShouldCompleteAdvisoryWithoutAccounting() throws Exception {
        String docId = upload("advisory.pdf", "content INV-001", "INVOICE");
        ocrService.processOcr(DocumentOcrRequestedEvent.create(
                UUID.fromString(docId), UUID.fromString(orgId), UUID.fromString(ownerUserId)));
        long invBefore = invoiceRepository.count();
        long expBefore = expenseRepository.count();
        long jeBefore = journalEntryRepository.count();

        extractionService.requestExtraction(UUID.fromString(ownerUserId), UUID.fromString(docId));
        extractionService.processExtraction(DocumentExtractionRequestedEvent.create(
                UUID.fromString(docId), UUID.fromString(orgId), UUID.fromString(ownerUserId)));
        var ext = extractionRepository.findByDocumentId(UUID.fromString(docId)).orElseThrow();
        assertThat(ext.getStatus()).isEqualTo(DocumentExtractionStatus.COMPLETED);
        assertThat(ext.getStructuredData()).isNotEmpty();
        // advisory only — no accounting records
        assertThat(invoiceRepository.count()).isEqualTo(invBefore);
        assertThat(expenseRepository.count()).isEqualTo(expBefore);
        assertThat(journalEntryRepository.count()).isEqualTo(jeBefore);
        // document status remains OCR-owned (PROCESSED), not changed by extraction
        var doc = documentRepository.findById(UUID.fromString(docId)).orElseThrow();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PROCESSED);
    }

    @Test
    void extractionWorkerShouldRejectWhenOcrIncomplete() throws Exception {
        String docId = upload("incomplete.pdf", "content", "INVOICE");
        // no OCR at all
        assertThatThrownBy(() -> extractionService.processExtraction(
                DocumentExtractionRequestedEvent.create(
                        UUID.fromString(docId), UUID.fromString(orgId), UUID.fromString(ownerUserId))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractionWorkerShouldRejectTenantMismatch() throws Exception {
        String docId = upload("emismatch.pdf", "content", "INVOICE");
        ocrService.processOcr(DocumentOcrRequestedEvent.create(
                UUID.fromString(docId), UUID.fromString(orgId), UUID.fromString(ownerUserId)));
        var bad = DocumentExtractionRequestedEvent.create(
                UUID.fromString(docId), UUID.randomUUID(), UUID.fromString(ownerUserId));
        assertThatThrownBy(() -> extractionService.processExtraction(bad))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractionWorkerShouldBeIdempotent() throws Exception {
        String docId = upload("ext-idem.pdf", "content", "INVOICE");
        ocrService.processOcr(DocumentOcrRequestedEvent.create(
                UUID.fromString(docId), UUID.fromString(orgId), UUID.fromString(ownerUserId)));
        var event = DocumentExtractionRequestedEvent.create(
                UUID.fromString(docId), UUID.fromString(orgId), UUID.fromString(ownerUserId));
        extractionService.requestExtraction(UUID.fromString(ownerUserId), UUID.fromString(docId));
        extractionService.processExtraction(event);
        long count = extractionRepository.count();
        extractionService.processExtraction(event);
        assertThat(extractionRepository.count()).isEqualTo(count);
        assertThat(extractionRepository.findByDocumentId(UUID.fromString(docId)).orElseThrow().getStatus())
                .isEqualTo(DocumentExtractionStatus.COMPLETED);
    }

    // ---- Review integration ----

    @Test
    void extractionCompletionShouldNotAutoApproveAndReprocessingInvalidates() throws Exception {
        String docId = upload("review.pdf", "content", "INVOICE");
        ocrService.processOcr(DocumentOcrRequestedEvent.create(
                UUID.fromString(docId), UUID.fromString(orgId), UUID.fromString(ownerUserId)));
        extractionService.processExtraction(DocumentExtractionRequestedEvent.create(
                UUID.fromString(docId), UUID.fromString(orgId), UUID.fromString(ownerUserId)));
        // no auto review
        assertThat(reviewRepository.findByDocumentId(UUID.fromString(docId))).isEmpty();
        // create + approve via API
        mockMvc.perform(post("/api/v1/documents/{documentId}/review", docId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/documents/{documentId}/review/approve", docId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        // reprocess invalidates
        extractionService.requestExtraction(UUID.fromString(ownerUserId), UUID.fromString(docId));
        extractionService.processExtraction(DocumentExtractionRequestedEvent.create(
                UUID.fromString(docId), UUID.fromString(orgId), UUID.fromString(ownerUserId)));
        mockMvc.perform(get("/api/v1/documents/{documentId}/review", docId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    // ---- DLQ / config / security ----

    @Test
    void dlqEventShouldContainOnlySafeMetadata() {
        var ocrEvent = DocumentOcrRequestedEvent.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var dlq = DocumentProcessingDlqEvent.fromOcr(ocrEvent, "Document not found: xyz /tmp/storage/secret");
        assertThat(dlq.originalEventId()).isEqualTo(ocrEvent.eventId());
        assertThat(dlq.documentId()).isEqualTo(ocrEvent.documentId());
        assertThat(dlq.processingType()).isEqualTo("OCR");
        assertThat(dlq.failureReason()).isNotNull();
        assertThat(dlq.failureReason().length()).isLessThanOrEqualTo(1000);
        String json;
        try {
            json = objectMapper.writeValueAsString(dlq);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(json).doesNotContain("password");
        assertThat(json).doesNotContain("jwt");
        assertThat(json).doesNotContain("extractedText");
        assertThat(json).doesNotContain("rawResponse");

        var extEvent = DocumentExtractionRequestedEvent.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var dlq2 = DocumentProcessingDlqEvent.fromExtraction(extEvent, "fail");
        assertThat(dlq2.processingType()).isEqualTo("EXTRACTION");
    }

    @Test
    void kafkaRetryAndTopicConfigShouldHaveSaneDefaults() {
        // topics via constants
        assertThat(KafkaTopics.DOCUMENT_OCR).isEqualTo("accounting.document.ocr");
        assertThat(KafkaTopics.DOCUMENT_EXTRACTION).isEqualTo("accounting.document.extraction");
        assertThat(KafkaTopics.DOCUMENT_DLQ).isEqualTo("accounting.document.processing.dlq");
        // consumer groups distinct
        assertThat(KafkaTopics.OCR_CONSUMER_GROUP).isNotEqualTo(KafkaTopics.EXTRACTION_CONSUMER_GROUP);
        // retry bounds
        assertThat(kafkaProperties.getConsumer().getMaxRetries()).isEqualTo(3);
        assertThat(kafkaProperties.getConsumer().getBackoffMs()).isEqualTo(1000L);
    }
}
