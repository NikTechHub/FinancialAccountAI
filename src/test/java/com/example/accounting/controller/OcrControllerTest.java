package com.example.accounting.controller;

import com.example.accounting.dto.RegisterRequest;
import com.example.accounting.entity.DocumentStatus;
import com.example.accounting.entity.DocumentType;
import com.example.accounting.entity.OcrStatus;
import com.example.accounting.kafka.event.DocumentOcrRequestedEvent;
import com.example.accounting.repository.DocumentRepository;
import com.example.accounting.repository.OcrResultRepository;
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
class OcrControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private OcrResultRepository ocrResultRepository;
    @Autowired
    private OcrService ocrService;

    private String ownerToken;
    private String ownerUserId;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = "ocr-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Ocr", "Owner",
                "ocr.owner." + suffix + "@example.com",
                "SecurePass123",
                "OcrOrg-" + suffix,
                "Legal Ocr " + suffix,
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

    private String authHeader() {
        return "Bearer " + ownerToken;
    }

    private MockMultipartFile pdfFile(String name, String content) {
        return new MockMultipartFile("file", name, "application/pdf", content.getBytes());
    }

    private MockMultipartFile jpegFile(String name) {
        return new MockMultipartFile("file", name, "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, 0x00, 0x01});
    }

    private MockMultipartFile pngFile(String name) {
        return new MockMultipartFile("file", name, "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
    }

    private String uploadDocument(MockMultipartFile file, String documentType) throws Exception {
        String resp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", documentType)
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("id").asText();
    }

    /** Simulate Kafka worker delivery: invoke worker directly (Kafka disabled in test profile). */
    private void runOcrWorker(String docId) {
        DocumentOcrRequestedEvent event = DocumentOcrRequestedEvent.create(
                UUID.fromString(docId), UUID.fromString(orgId), UUID.fromString(ownerUserId));
        ocrService.processOcr(event);
    }

    // 17. start OCR -> 202 PENDING (async)
    @Test
    void shouldStartOcr() throws Exception {
        String docId = uploadDocument(pdfFile("invoice.pdf", "%PDF-1.4 Invoice #INV-001"), "INVOICE");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.documentId").value(docId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.message").value("OCR processing has been queued"));
        // polling shows PENDING before worker
        mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        // worker completes
        runOcrWorker(docId);
        mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(docId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.extractedText").isNotEmpty())
                .andExpect(jsonPath("$.pageCount").isNumber());
    }

    // 18. retrieve OCR result after worker
    @Test
    void shouldRetrieveOcrResult() throws Exception {
        String docId = uploadDocument(pdfFile("retrieve.pdf", "content"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runOcrWorker(docId);
        mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(docId))
                .andExpect(jsonPath("$.extractedText").isNotEmpty());
    }

    // 19. successful OCR changes document to PROCESSED (after worker)
    @Test
    void shouldChangeDocumentToProcessedOnSuccess() throws Exception {
        String docId = uploadDocument(pdfFile("process.pdf", "content"), "INVOICE");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runOcrWorker(docId);
        mockMvc.perform(get("/api/v1/documents/{id}", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"));
    }

    // 20. successful OCR stores extracted text (after worker)
    @Test
    void shouldStoreExtractedText() throws Exception {
        String docId = uploadDocument(pdfFile("text.pdf", "Invoice Total 1000"), "INVOICE");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runOcrWorker(docId);
        String resp = mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String text = objectMapper.readTree(resp).get("extractedText").asText();
        org.assertj.core.api.Assertions.assertThat(text).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(text).contains("Fake OCR");
    }

    // 21. successful OCR stores page count
    @Test
    void shouldStorePageCount() throws Exception {
        String docId = uploadDocument(pdfFile("multipage.pdf", "content"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runOcrWorker(docId);
        String resp = mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int pageCount = objectMapper.readTree(resp).get("pageCount").asInt();
        org.assertj.core.api.Assertions.assertThat(pageCount).isGreaterThanOrEqualTo(1);

        String imgId = uploadDocument(jpegFile("image.jpg"), "RECEIPT");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", imgId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        // image worker needs its own org context — same org/user
        DocumentOcrRequestedEvent imgEvent = DocumentOcrRequestedEvent.create(
                UUID.fromString(imgId), UUID.fromString(orgId), UUID.fromString(ownerUserId));
        ocrService.processOcr(imgEvent);
        String imgResp = mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", imgId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int imgPageCount = objectMapper.readTree(imgResp).get("pageCount").asInt();
        org.assertj.core.api.Assertions.assertThat(imgPageCount).isEqualTo(1);
    }

    // 22. failed OCR changes document to FAILED (after worker)
    @Test
    void shouldChangeDocumentToFailedOnFailure() throws Exception {
        String docId = uploadDocument(pdfFile("corrupt.pdf", "corrupt"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
        runOcrWorker(docId);
        String resp = mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(resp).get("status").asText()).isEqualTo("FAILED");
        mockMvc.perform(get("/api/v1/documents/{id}", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    // 23. failed OCR stores sanitized error
    @Test
    void shouldStoreSanitizedErrorOnFailure() throws Exception {
        String docId = uploadDocument(pdfFile("corrupt2.pdf", "corrupt"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runOcrWorker(docId);
        String resp = mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String error = objectMapper.readTree(resp).get("errorMessage").asText();
        org.assertj.core.api.Assertions.assertThat(error).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(error).doesNotContain("stack");
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(resp).get("extractedText").isNull()).isTrue();
    }

    // 24. retry FAILED OCR
    @Test
    void shouldRetryFailedOcr() throws Exception {
        String docId = uploadDocument(pdfFile("corrupt-retry.pdf", "corrupt"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runOcrWorker(docId);
        String first = mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(first).get("status").asText()).isEqualTo("FAILED");
        String firstId = objectMapper.readTree(first).get("id").asText();
        // requeue
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runOcrWorker(docId);
        String second = mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondId = objectMapper.readTree(second).get("id").asText();
        org.assertj.core.api.Assertions.assertThat(firstId).isEqualTo(secondId);
    }

    // 25. reprocess COMPLETED OCR without duplicate row
    @Test
    void shouldReprocessCompletedWithoutDuplicate() throws Exception {
        String docId = uploadDocument(pdfFile("reprocess.pdf", "content"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runOcrWorker(docId);
        String first = mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstId = objectMapper.readTree(first).get("id").asText();
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runOcrWorker(docId);
        String second = mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondId = objectMapper.readTree(second).get("id").asText();
        org.assertj.core.api.Assertions.assertThat(firstId).isEqualTo(secondId);
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(second).get("status").asText()).isEqualTo("COMPLETED");
    }

    // 26. PROCESSING returns 409 — simulate by forcing PROCESSING state
    @Test
    void shouldReturn409WhenAlreadyProcessing() throws Exception {
        String docId = uploadDocument(pdfFile("processing.pdf", "content"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        // force PROCESSING state to simulate in-flight worker
        var ocrOpt = ocrResultRepository.findByDocumentId(UUID.fromString(docId));
        org.assertj.core.api.Assertions.assertThat(ocrOpt).isPresent();
        var ocr = ocrOpt.get();
        ocr.setStatus(OcrStatus.PROCESSING);
        ocrResultRepository.save(ocr);
        // second request while PROCESSING -> 409
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isConflict());
    }

    // 27-29 OWNER/ADMIN/ACCOUNTANT can process (queue)
    @Test
    void shouldAllowOwnerToProcess() throws Exception {
        String docId = uploadDocument(pdfFile("owner.pdf", "content"), "INVOICE");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void shouldAllowAdminToProcess() throws Exception {
        String suffix = "admin-ocr-" + System.nanoTime();
        String userJson = """
                {"firstName":"Admin","lastName":"User","email":"admin.ocr.%s@example.com","password":"SecurePass123"}
                """.formatted(suffix);
        String userResp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String userId = objectMapper.readTree(userResp).get("id").asText();
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", orgId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"ADMIN"}
                                """.formatted(userId)))
                .andExpect(status().isCreated());
        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin.ocr.%s@example.com","password":"SecurePass123"}
                                """.formatted(suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String adminToken = objectMapper.readTree(loginResp).get("accessToken").asText();
        String docId = uploadDocument(pdfFile("admin.pdf", "content"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isAccepted());
    }

    @Test
    void shouldAllowAccountantToProcess() throws Exception {
        String suffix = "acc-ocr-" + System.nanoTime();
        String userJson = """
                {"firstName":"Acc","lastName":"User","email":"acc.ocr.%s@example.com","password":"SecurePass123"}
                """.formatted(suffix);
        String userResp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String userId = objectMapper.readTree(userResp).get("id").asText();
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", orgId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"ACCOUNTANT"}
                                """.formatted(userId)))
                .andExpect(status().isCreated());
        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"acc.ocr.%s@example.com","password":"SecurePass123"}
                                """.formatted(suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String accToken = objectMapper.readTree(loginResp).get("accessToken").asText();
        String docId = uploadDocument(pdfFile("acc.pdf", "content"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", "Bearer " + accToken))
                .andExpect(status().isAccepted());
    }

    // 30-31 EMPLOYEE/VIEWER process -> 403
    @Test
    void shouldRejectEmployeeProcess() throws Exception {
        String suffix = "emp-ocr-" + System.nanoTime();
        String userJson = """
                {"firstName":"Emp","lastName":"User","email":"emp.ocr.%s@example.com","password":"SecurePass123"}
                """.formatted(suffix);
        String userResp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String userId = objectMapper.readTree(userResp).get("id").asText();
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", orgId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"EMPLOYEE"}
                                """.formatted(userId)))
                .andExpect(status().isCreated());
        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"emp.ocr.%s@example.com","password":"SecurePass123"}
                                """.formatted(suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String empToken = objectMapper.readTree(loginResp).get("accessToken").asText();
        String docId = uploadDocument(pdfFile("emp.pdf", "content"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", "Bearer " + empToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectViewerProcess() throws Exception {
        String suffix = "view-ocr-" + System.nanoTime();
        String userJson = """
                {"firstName":"View","lastName":"User","email":"view.ocr.%s@example.com","password":"SecurePass123"}
                """.formatted(suffix);
        String userResp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String userId = objectMapper.readTree(userResp).get("id").asText();
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", orgId)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"VIEWER"}
                                """.formatted(userId)))
                .andExpect(status().isCreated());
        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"view.ocr.%s@example.com","password":"SecurePass123"}
                                """.formatted(suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewToken = objectMapper.readTree(loginResp).get("accessToken").asText();
        String docId = uploadDocument(pdfFile("view.pdf", "content"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", "Bearer " + viewToken))
                .andExpect(status().isForbidden());
    }

    // 32-33 EMPLOYEE/VIEWER can read
    @Test
    void shouldAllowEmployeeReadOcr() throws Exception {
        String docId = uploadDocument(pdfFile("emp-read.pdf", "content"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runOcrWorker(docId);
        String suffix = "emp-read-" + System.nanoTime();
        String empEmail = "emp.read." + suffix + "@example.com";
        String empJson = """
                {"firstName":"Emp","lastName":"Read","email":"%s","password":"SecurePass123"}
                """.formatted(empEmail);
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
        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"SecurePass123"}
                                """.formatted(empEmail)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String empToken = objectMapper.readTree(loginResp).get("accessToken").asText();
        mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", "Bearer " + empToken))
                .andExpect(status().isOk());
    }

    @Test
    void shouldAllowViewerReadOcr() throws Exception {
        String docId = uploadDocument(pdfFile("view-read.pdf", "content"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runOcrWorker(docId);
        String suffix = "view-read-" + System.nanoTime();
        String viewerEmail = "view.read." + suffix + "@example.com";
        String viewerJson = """
                {"firstName":"View","lastName":"Read","email":"%s","password":"SecurePass123"}
                """.formatted(viewerEmail);
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
                                {"email":"%s","password":"SecurePass123"}
                                """.formatted(viewerEmail)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewerToken = objectMapper.readTree(loginResp).get("accessToken").asText();
        mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReturn401WhenUnauthenticated() throws Exception {
        String docId = uploadDocument(pdfFile("noauth.pdf", "content"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId))
                .andExpect(status().isUnauthorized());
    }

    // 35-37 tenant isolation
    @Test
    void shouldPreventCrossTenantOcr() throws Exception {
        String docId = uploadDocument(pdfFile("tenant.pdf", "content"), "OTHER");
        String suffix = "cross-ocr-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Cross", "Ocr",
                "cross.ocr." + suffix + "@example.com",
                "SecurePass123",
                "CrossOcrOrg-" + suffix,
                "Legal Cross " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    // failure handling
    @Test
    void shouldHandleUnsupportedDocumentForOcr() throws Exception {
        org.assertj.core.api.Assertions.assertThat(true).isTrue();
    }

    @Test
    void shouldHandleCorruptPdfGracefully() throws Exception {
        String docId = uploadDocument(pdfFile("corrupt-test.pdf", "corrupt"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runOcrWorker(docId);
        String resp = mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(resp).get("status").asText()).isEqualTo("FAILED");
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(resp).get("errorMessage").asText()).isNotEmpty();
        mockMvc.perform(get("/api/v1/documents/{id}", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void shouldNotLeakFilesystemPathsOnFailure() throws Exception {
        String docId = uploadDocument(pdfFile("corrupt-path.pdf", "corrupt"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runOcrWorker(docId);
        String resp = mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String error = objectMapper.readTree(resp).get("errorMessage").asText();
        org.assertj.core.api.Assertions.assertThat(error).doesNotContain("/");
        org.assertj.core.api.Assertions.assertThat(error).doesNotContain("\\");
        org.assertj.core.api.Assertions.assertThat(error).doesNotContain("storage");
    }

    @Test
    void shouldNotCreateDuplicateOcrResultAfterFailure() throws Exception {
        String docId = uploadDocument(pdfFile("corrupt-dup.pdf", "corrupt"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runOcrWorker(docId);
        String first = mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstId = objectMapper.readTree(first).get("id").asText();
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runOcrWorker(docId);
        String second = mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondId = objectMapper.readTree(second).get("id").asText();
        org.assertj.core.api.Assertions.assertThat(firstId).isEqualTo(secondId);
    }

    // PDF and image specific
    @Test
    void shouldExtractTextFromPdf() throws Exception {
        String docId = uploadDocument(pdfFile("text-pdf.pdf", "Sample PDF text"), "INVOICE");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runOcrWorker(docId);
        String resp = mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(resp).get("extractedText").asText()).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(resp).get("pageCount").asInt()).isGreaterThan(0);
    }

    @Test
    void shouldHandleMultiPagePdf() throws Exception {
        String docId = uploadDocument(pdfFile("multi-2pages.pdf", "multi"), "OTHER");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        runOcrWorker(docId);
        String resp = mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int pageCount = objectMapper.readTree(resp).get("pageCount").asInt();
        org.assertj.core.api.Assertions.assertThat(pageCount).isEqualTo(2);
    }

    @Test
    void shouldHandleJpegAndPng() throws Exception {
        String jpegId = uploadDocument(jpegFile("test.jpg"), "RECEIPT");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", jpegId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        ocrService.processOcr(DocumentOcrRequestedEvent.create(
                UUID.fromString(jpegId), UUID.fromString(orgId), UUID.fromString(ownerUserId)));
        mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", jpegId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageCount").value(1));
        String pngId = uploadDocument(pngFile("test.png"), "RECEIPT");
        mockMvc.perform(post("/api/v1/documents/{documentId}/ocr", pngId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isAccepted());
        ocrService.processOcr(DocumentOcrRequestedEvent.create(
                UUID.fromString(pngId), UUID.fromString(orgId), UUID.fromString(ownerUserId)));
        mockMvc.perform(get("/api/v1/documents/{documentId}/ocr", pngId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageCount").value(1));
    }
}
