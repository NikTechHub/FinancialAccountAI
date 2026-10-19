package com.example.accounting.controller;

import com.example.accounting.dto.RegisterRequest;
import com.example.accounting.entity.DocumentExtractionStatus;
import com.example.accounting.kafka.event.DocumentExtractionRequestedEvent;
import com.example.accounting.kafka.event.DocumentOcrRequestedEvent;
import com.example.accounting.repository.DocumentExtractionRepository;
import com.example.accounting.service.DocumentExtractionService;
import com.example.accounting.service.OcrService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentExtractionControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OcrService ocrService;
    @Autowired
    private DocumentExtractionService extractionService;
    @Autowired
    private DocumentExtractionRepository extractionRepository;

    private String ownerToken;
    private String ownerUserId;
    private String orgId;
    private String documentId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = "ext-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Ext", "Owner",
                "ext.owner." + suffix + "@example.com",
                "SecurePass123",
                "ExtOrg-" + suffix,
                "Legal Ext " + suffix,
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

        // create a document and run OCR to completion via async + worker
        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", "%PDF-1.4 Invoice #INV-001 Total: 1000".getBytes());
        String docResp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "INVOICE")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        documentId = objectMapper.readTree(docResp).get("id").asText();

        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", documentId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
        ocrService.processOcr(DocumentOcrRequestedEvent.create(
                UUID.fromString(documentId), UUID.fromString(orgId), UUID.fromString(ownerUserId)));
        mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", documentId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    private String authHeader() {
        return "Bearer " + ownerToken;
    }

    private void runExtractionWorker(String docId) {
        extractionService.processExtraction(DocumentExtractionRequestedEvent.create(
                UUID.fromString(docId), UUID.fromString(orgId), UUID.fromString(ownerUserId)));
    }

    private String queueExtractionAndComplete(String docId) throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
        // polling shows PENDING
        mockMvc.perform(get("/api/v1/documents/{documentId}/extraction", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        runExtractionWorker(docId);
        return mockMvc.perform(get("/api/v1/documents/{documentId}/extraction", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void shouldStartExtractionSuccessfully() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "invoice2.pdf", "application/pdf", "Invoice INV-002".getBytes());
        String docResp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "INVOICE")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String newDocId = objectMapper.readTree(docResp).get("id").asText();
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", newDocId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        ocrService.processOcr(DocumentOcrRequestedEvent.create(
                UUID.fromString(newDocId), UUID.fromString(orgId), UUID.fromString(ownerUserId)));
        // queue extraction -> 202 PENDING
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", newDocId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.documentId").value(newDocId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.message").value("AI extraction has been queued"));
        // worker completes
        runExtractionWorker(newDocId);
        mockMvc.perform(get("/api/v1/documents/{documentId}/extraction", newDocId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(newDocId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.structuredData").isNotEmpty())
                .andExpect(jsonPath("$.model").isNotEmpty())
                .andExpect(jsonPath("$.promptVersion").value("invoice-extraction-v1"));
    }

    @Test
    void shouldReturn401WhenUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", documentId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documents/{documentId}/extraction", documentId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn403ForViewerAndEmployee() throws Exception {
        String suffix = "view-ext-" + System.nanoTime();
        String viewerJson = """
                {"firstName":"Viewer","lastName":"User","email":"viewer.ext.%s@example.com","password":"SecurePass123"}
                """.formatted(suffix);
        String viewerResp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(viewerJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String viewerUserId = objectMapper.readTree(viewerResp).get("id").asText();
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", orgId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"VIEWER"}
                                """.formatted(viewerUserId)))
                .andExpect(status().isCreated());
        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"viewer.ext.%s@example.com","password":"SecurePass123"}
                                """.formatted(suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewerToken = objectMapper.readTree(loginResp).get("accessToken").asText();
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());

        // first create extraction as owner (queue + worker)
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runExtractionWorker(documentId);
        mockMvc.perform(get("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        String empSuffix = "emp-ext-" + System.nanoTime();
        String empJson = """
                {"firstName":"Emp","lastName":"User","email":"emp.ext.%s@example.com","password":"SecurePass123"}
                """.formatted(empSuffix);
        String empResp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(empJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String empUserId = objectMapper.readTree(empResp).get("id").asText();
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", orgId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"EMPLOYEE"}
                                """.formatted(empUserId)))
                .andExpect(status().isCreated());
        String empLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"emp.ext.%s@example.com","password":"SecurePass123"}
                                """.formatted(empSuffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String empToken = objectMapper.readTree(empLogin).get("accessToken").asText();
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", "Bearer " + empToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", "Bearer " + empToken))
                .andExpect(status().isOk());
    }

    @Test
    void shouldAllowOwnerAdminAccountantToExtract() throws Exception {
        String suffix = "acc-ext-" + System.nanoTime();
        String accJson = """
                {"firstName":"Acc","lastName":"User","email":"acc.ext.%s@example.com","password":"SecurePass123"}
                """.formatted(suffix);
        String accResp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String accUserId = objectMapper.readTree(accResp).get("id").asText();
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", orgId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"ACCOUNTANT"}
                                """.formatted(accUserId)))
                .andExpect(status().isCreated());
        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"acc.ext.%s@example.com","password":"SecurePass123"}
                                """.formatted(suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String accToken = objectMapper.readTree(loginResp).get("accessToken").asText();
        MockMultipartFile file = new MockMultipartFile("file", "acc.pdf", "application/pdf", "content".getBytes());
        String docResp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "INVOICE")
                        .header("Authorization", "Bearer " + accToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String newDocId = objectMapper.readTree(docResp).get("id").asText();
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", newDocId)
                        .header("Authorization", "Bearer " + accToken))
                .andExpect(status().isAccepted());
        ocrService.processOcr(DocumentOcrRequestedEvent.create(
                UUID.fromString(newDocId), UUID.fromString(orgId), UUID.fromString(accUserId)));
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", newDocId)
                        .header("Authorization", "Bearer " + accToken))
                .andExpect(status().isAccepted());
        // complete extraction with acc user
        extractionService.processExtraction(DocumentExtractionRequestedEvent.create(
                UUID.fromString(newDocId), UUID.fromString(orgId), UUID.fromString(accUserId)));
        mockMvc.perform(get("/api/v1/documents/{documentId}/extraction", newDocId)
                        .header("Authorization", "Bearer " + accToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void shouldRequireOcrCompleted() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "noocr.pdf", "application/pdf", "content".getBytes());
        String docResp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "INVOICE")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String newDocId = objectMapper.readTree(docResp).get("id").asText();
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", newDocId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldHandleCrossTenantExtraction() throws Exception {
        String suffix = "cross-ext-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Cross", "Ext",
                "cross.ext." + suffix + "@example.com",
                "SecurePass123",
                "CrossExtOrg-" + suffix,
                "Legal Cross " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldSupportReprocessing() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runExtractionWorker(documentId);
        String first = mockMvc.perform(get("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstId = objectMapper.readTree(first).get("id").asText();
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runExtractionWorker(documentId);
        String second = mockMvc.perform(get("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondId = objectMapper.readTree(second).get("id").asText();
        org.assertj.core.api.Assertions.assertThat(firstId).isEqualTo(secondId);
    }

    @Test
    void shouldReturn409WhenAlreadyProcessing() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        // force PROCESSING
        var extOpt = extractionRepository.findByDocumentId(UUID.fromString(documentId));
        org.assertj.core.api.Assertions.assertThat(extOpt).isPresent();
        var ext = extOpt.get();
        ext.setStatus(DocumentExtractionStatus.PROCESSING);
        extractionRepository.save(ext);
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isConflict());
        // cleanup: reset to PENDING so other tests not affected (new doc per test via BeforeEach, so fine)
    }

    @Test
    void shouldValidateStructuredData() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runExtractionWorker(documentId);
        String resp = mockMvc.perform(get("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(resp).get("structuredData").get("invoiceNumber").asText()).isNotEmpty();
    }

    @Test
    void shouldNotExposeRawSecrets() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runExtractionWorker(documentId);
        String resp = mockMvc.perform(get("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(resp).doesNotContain("apiKey");
        org.assertj.core.api.Assertions.assertThat(resp).doesNotContain("secret");
        org.assertj.core.api.Assertions.assertThat(resp).doesNotContain("rawResponse");
    }

    @Test
    void shouldHandleTenantIsolationForOcr() throws Exception {
        String suffix = "cross-ocr-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Cross", "Ocr2",
                "cross.ocr2." + suffix + "@example.com",
                "SecurePass123",
                "CrossOcrOrg2-" + suffix,
                "Legal Cross2 " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", documentId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }
}
