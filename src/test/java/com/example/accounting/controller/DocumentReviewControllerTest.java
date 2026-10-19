package com.example.accounting.controller;

import com.example.accounting.dto.RegisterRequest;
import com.example.accounting.dto.StructuredInvoiceExtraction;
import com.example.accounting.kafka.event.DocumentExtractionRequestedEvent;
import com.example.accounting.kafka.event.DocumentOcrRequestedEvent;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentReviewControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OcrService ocrService;
    @Autowired private DocumentExtractionService extractionService;

    private String ownerToken;
    private String ownerUserId;
    private String orgId;
    private String documentId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = "rev-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Rev", "Owner",
                "rev.owner." + suffix + "@example.com",
                "SecurePass123",
                "RevOrg-" + suffix,
                "Legal Rev " + suffix,
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

        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", "%PDF-1.4 Invoice #INV-001 Total: 1000".getBytes());
        String docResp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "INVOICE")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        documentId = objectMapper.readTree(docResp).get("id").asText();

        // async OCR: queue then worker
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

        // async extraction: queue then worker
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
        extractionService.processExtraction(DocumentExtractionRequestedEvent.create(
                UUID.fromString(documentId), UUID.fromString(orgId), UUID.fromString(ownerUserId)));
        mockMvc.perform(get("/api/v1/documents/{documentId}/extraction", documentId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    private String auth() { return "Bearer " + ownerToken; }

    @Test
    void fullReviewFlow() throws Exception {
        String create = mockMvc.perform(post("/api/v1/documents/{documentId}/review", documentId)
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.originalAiData").isNotEmpty())
                .andExpect(jsonPath("$.reviewedData").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        System.out.println("CREATE: " + create);
        mockMvc.perform(get("/api/v1/documents/{documentId}/review", documentId)
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        StructuredInvoiceExtraction original = objectMapper.readValue(objectMapper.readTree(create).get("reviewedData").toString(), StructuredInvoiceExtraction.class);
        StructuredInvoiceExtraction updated = new StructuredInvoiceExtraction(
                original.documentType(), original.invoiceNumber(), original.invoiceDate(), original.dueDate(),
                original.currency(), original.vendorName(), original.vendorTaxIdentifier(), original.vendorEmail(), original.vendorPhone(),
                original.customerName(), original.customerTaxIdentifier(),
                new BigDecimal("2000.00"), original.taxAmount(), original.discountAmount(), new BigDecimal("2180.00"),
                original.paymentTerms(), original.notes(), original.lineItems()
        );
        String updateJson = objectMapper.writeValueAsString(new com.example.accounting.dto.UpdateDocumentReviewRequest(updated));
        mockMvc.perform(put("/api/v1/documents/{documentId}/review", documentId)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewedData.totalAmount").value(2180.00));

        mockMvc.perform(post("/api/v1/documents/{documentId}/review/approve", documentId)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewComment\":\"Looks good\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewedBy").isNotEmpty())
                .andExpect(jsonPath("$.reviewedAt").isNotEmpty());

        mockMvc.perform(put("/api/v1/documents/{documentId}/review", documentId)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isConflict());

        String get = mockMvc.perform(get("/api/v1/documents/{documentId}/review", documentId)
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        System.out.println("GET AFTER APPROVE: " + get);
    }

    @Test
    void rejectAndReopen() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/review", documentId).header("Authorization", auth())).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/documents/{documentId}/review/reject", documentId)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewComment\":\"Wrong vendor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(post("/api/v1/documents/{documentId}/review/reopen", documentId)
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(post("/api/v1/documents/{documentId}/review/approve", documentId)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/review", documentId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documents/{documentId}/review", documentId)).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn403ForViewer() throws Exception {
        String suffix = "view-rev-" + System.nanoTime();
        String viewerJson = """
                {"firstName":"Viewer","lastName":"User","email":"viewer.rev.%s@example.com","password":"SecurePass123"}
                """.formatted(suffix);
        String viewerResp = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(viewerJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String viewerUserId = objectMapper.readTree(viewerResp).get("id").asText();
        mockMvc.perform(post("/api/v1/organizations/{organizationId}/members", orgId)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"VIEWER"}
                                """.formatted(viewerUserId)))
                .andExpect(status().isCreated());
        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"viewer.rev.%s@example.com","password":"SecurePass123"}
                                """.formatted(suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewerToken = objectMapper.readTree(loginResp).get("accessToken").asText();

        mockMvc.perform(post("/api/v1/documents/{documentId}/review", documentId).header("Authorization", auth())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/documents/{documentId}/review", documentId).header("Authorization", "Bearer " + viewerToken)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/documents/{documentId}/review", documentId).header("Authorization", "Bearer " + viewerToken)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/documents/{documentId}/review/approve", documentId).header("Authorization", "Bearer " + viewerToken).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void crossTenantShould404() throws Exception {
        String suffix = "cross-rev-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Cross", "Rev",
                "cross.rev." + suffix + "@example.com",
                "SecurePass123",
                "CrossRevOrg-" + suffix,
                "Legal Cross " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        mockMvc.perform(post("/api/v1/documents/{documentId}/review", documentId).header("Authorization", "Bearer " + otherToken)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/documents/{documentId}/review", documentId).header("Authorization", "Bearer " + otherToken)).andExpect(status().isNotFound());
    }

    @Test
    void reprocessingInvalidatesApproval() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/review", documentId).header("Authorization", auth())).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/documents/{documentId}/review/approve", documentId).header("Authorization", auth()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        // reprocess extraction via async queue + worker
        mockMvc.perform(post("/api/v1/documents/{documentId}/extraction", documentId).header("Authorization", auth())).andExpect(status().isAccepted());
        extractionService.processExtraction(DocumentExtractionRequestedEvent.create(
                UUID.fromString(documentId), UUID.fromString(orgId), UUID.fromString(ownerUserId)));
        mockMvc.perform(get("/api/v1/documents/{documentId}/review", documentId).header("Authorization", auth())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/documents/{documentId}/review", documentId).header("Authorization", auth())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void validationFailure() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/review", documentId).header("Authorization", auth())).andExpect(status().isOk());
        StructuredInvoiceExtraction invalid = new StructuredInvoiceExtraction(
                "INVOICE", "INV-001", "2026-01-15", null, "INR", "Vendor", null, null, null, "Customer", null,
                new BigDecimal("-100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "Net 30", null,
                List.of()
        );
        String json = objectMapper.writeValueAsString(new com.example.accounting.dto.UpdateDocumentReviewRequest(invalid));
        mockMvc.perform(put("/api/v1/documents/{documentId}/review", documentId).header("Authorization", auth()).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isBadRequest());
    }
}
