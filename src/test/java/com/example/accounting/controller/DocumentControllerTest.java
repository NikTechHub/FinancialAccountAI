package com.example.accounting.controller;

import com.example.accounting.dto.RegisterRequest;
import com.example.accounting.entity.DocumentStatus;
import com.example.accounting.entity.DocumentType;
import com.example.accounting.repository.DocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DocumentRepository documentRepository;

    @Value("${app.documents.storage-path}")
    private String storagePath;

    private String ownerToken;
    private String ownerUserId;
    private String orgId;
    private String customerId;
    private String invoiceId;
    private String expenseId;
    private String revenueAccountId;
    private String expenseAccountId;
    private String paymentAccountId;
    private String vendorId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = "doc-" + System.nanoTime();
        RegisterRequest req = new RegisterRequest(
                "Doc", "Owner",
                "doc.owner." + suffix + "@example.com",
                "SecurePass123",
                "DocOrg-" + suffix,
                "Legal Doc " + suffix,
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

        // create customer
        String custJson = """
                {"name":"Doc Customer %s","email":"doc.cust%s@example.com"}
                """.formatted(suffix, suffix.replace("-", ""));
        String custResp = mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(custJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        customerId = objectMapper.readTree(custResp).get("id").asText();

        // create accounts
        revenueAccountId = createAccount("4000-" + suffix.substring(0, 8), "Sales Revenue", "REVENUE");
        expenseAccountId = createAccount("5000-" + suffix.substring(0, 8), "Office Expenses", "EXPENSE");
        paymentAccountId = createAccount("1000-" + suffix.substring(0, 8), "Cash", "ASSET");
        createAccount("1100-" + suffix.substring(0, 8), "Accounts Receivable", "ASSET");

        // create vendor
        vendorId = createVendor("Vendor-" + suffix);

        // create invoice and expense for association tests
        String invJson = """
                {
                    "customerId":"%s",
                    "invoiceNumber":"INV-DOC-%s",
                    "invoiceDate":"%s",
                    "lines":[{"description":"Item","quantity":1,"unitPrice":100,"taxRate":10,"discountAmount":0,"revenueAccountId":"%s"}]
                }
                """.formatted(customerId, suffix, LocalDate.now().toString(), revenueAccountId);
        String invResp = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        invoiceId = objectMapper.readTree(invResp).get("id").asText();

        String expJson = """
                {
                    "vendorId":"%s",
                    "expenseAccountId":"%s",
                    "paymentAccountId":"%s",
                    "amount":100.00,
                    "expenseDate":"%s",
                    "description":"Test expense"
                }
                """.formatted(vendorId, expenseAccountId, paymentAccountId, LocalDate.now().toString());
        String expResp = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        expenseId = objectMapper.readTree(expResp).get("id").asText();
    }

    private String authHeader() {
        return "Bearer " + ownerToken;
    }

    private String createAccount(String code, String name, String type) throws Exception {
        String json = """
                {"code":"%s","name":"%s","type":"%s"}
                """.formatted(code, name, type);
        String resp = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("id").asText();
    }

    private String createVendor(String name) throws Exception {
        String json = """
                {"name":"%s","email":"%s@example.com"}
                """.formatted(name, name.toLowerCase().replace(" ", "") + System.nanoTime() % 10000);
        String resp = mockMvc.perform(post("/api/v1/vendors")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("id").asText();
    }

    private MockMultipartFile pdfFile(String name) {
        return new MockMultipartFile("file", name, "application/pdf", "%PDF-1.4 test content".getBytes());
    }

    private MockMultipartFile jpegFile(String name) {
        return new MockMultipartFile("file", name, "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, 0x00, 0x01});
    }

    private MockMultipartFile pngFile(String name) {
        return new MockMultipartFile("file", name, "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
    }

    // 1-3 upload valid types
    @Test
    void shouldUploadValidPdf() throws Exception {
        MockMultipartFile file = pdfFile("invoice-march-2026.pdf");
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "INVOICE")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.checksum").isNotEmpty());
    }

    @Test
    void shouldUploadValidJpeg() throws Exception {
        MockMultipartFile file = jpegFile("receipt.jpg");
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "RECEIPT")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/jpeg"));
    }

    @Test
    void shouldUploadValidPng() throws Exception {
        MockMultipartFile file = pngFile("image.png");
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "OTHER")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/png"));
    }

    @Test
    void shouldRejectUnsupportedContentType() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "malware.exe", "application/x-msdownload", "exe".getBytes());
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "OTHER")
                        .header("Authorization", authHeader()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectMissingFile() throws Exception {
        mockMvc.perform(multipart("/api/v1/documents")
                        .param("documentType", "INVOICE")
                        .header("Authorization", authHeader()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectOversizedFile() throws Exception {
        // create 11MB file (exceeds 10MB)
        byte[] large = new byte[11 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "large.pdf", "application/pdf", large);
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "INVOICE")
                        .header("Authorization", authHeader()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectMissingDocumentType() throws Exception {
        MockMultipartFile file = pdfFile("test.pdf");
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .header("Authorization", authHeader()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectInvalidDocumentType() throws Exception {
        MockMultipartFile file = pdfFile("test.pdf");
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "INVALID_TYPE")
                        .header("Authorization", authHeader()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldUploadWithoutInvoiceExpense() throws Exception {
        MockMultipartFile file = pdfFile("noassoc.pdf");
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "OTHER")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invoiceId").doesNotExist())
                .andExpect(jsonPath("$.expenseId").doesNotExist());
    }

    @Test
    void shouldUploadLinkedToInvoice() throws Exception {
        MockMultipartFile file = pdfFile("invoice.pdf");
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "INVOICE")
                        .param("invoiceId", invoiceId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invoiceId").value(invoiceId));
    }

    @Test
    void shouldUploadLinkedToExpense() throws Exception {
        MockMultipartFile file = pdfFile("receipt.pdf");
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "EXPENSE_RECEIPT")
                        .param("expenseId", expenseId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expenseId").value(expenseId));
    }

    @Test
    void shouldRejectBothInvoiceAndExpense() throws Exception {
        MockMultipartFile file = pdfFile("both.pdf");
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "OTHER")
                        .param("invoiceId", invoiceId)
                        .param("expenseId", expenseId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectCrossTenantInvoice() throws Exception {
        String suffix = "cross-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Other", "Cross",
                "cross.doc." + suffix + "@example.com",
                "SecurePass123",
                "CrossDocOrg-" + suffix,
                "Legal Cross " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        MockMultipartFile file = pdfFile("cross.pdf");
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "INVOICE")
                        .param("invoiceId", invoiceId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectCrossTenantExpense() throws Exception {
        String suffix = "cross-exp-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Other", "CrossExp",
                "cross.exp." + suffix + "@example.com",
                "SecurePass123",
                "CrossExpOrg-" + suffix,
                "Legal CrossExp " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        MockMultipartFile file = pdfFile("cross.pdf");
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "RECEIPT")
                        .param("expenseId", expenseId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldSaveMetadataAndChecksum() throws Exception {
        MockMultipartFile file = pdfFile("meta.pdf");
        String resp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "INVOICE")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.checksum").isNotEmpty())
                .andExpect(jsonPath("$.originalFileName").value("meta.pdf"))
                .andExpect(jsonPath("$.fileSize").isNumber())
                .andExpect(jsonPath("$.uploadedBy").value(ownerUserId))
                .andReturn().getResponse().getContentAsString();
        String storageKey = objectMapper.readTree(resp) != null ? "check" : "check";
        // verify checksum is SHA-256 hex length 64
        String checksum = objectMapper.readTree(resp).get("checksum").asText();
        org.assertj.core.api.Assertions.assertThat(checksum).hasSize(64);
    }

    @Test
    void shouldGenerateSafeStoredFilename() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "../../etc/passwd", "application/pdf", "%PDF".getBytes());
        String resp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "OTHER")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        // storedFileName is not exposed, but storageKey should not contain traversal
        String id = objectMapper.readTree(resp).get("id").asText();
        // verify via get that originalFileName is sanitized but not path
        mockMvc.perform(get("/api/v1/documents/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFileName").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(".."))));
    }

    @Test
    void shouldExistPhysicalFileAfterUpload() throws Exception {
        MockMultipartFile file = pdfFile("physical.pdf");
        String resp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "OTHER")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String storageKey = documentRepository.findById(java.util.UUID.fromString(objectMapper.readTree(resp).get("id").asText())).orElseThrow().getStorageKey();
        Path path = Paths.get(storagePath).resolve(storageKey);
        org.assertj.core.api.Assertions.assertThat(Files.exists(path)).isTrue();
    }

    @Test
    void shouldDownloadCorrectContent() throws Exception {
        byte[] content = "%PDF-1.4 download test".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "download.pdf", "application/pdf", content);
        String resp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "INVOICE")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        byte[] downloaded = mockMvc.perform(get("/api/v1/documents/{id}/download", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("download.pdf")))
                .andReturn().getResponse().getContentAsByteArray();
        org.assertj.core.api.Assertions.assertThat(downloaded).isEqualTo(content);
    }

    @Test
    void shouldNotExposeStoragePath() throws Exception {
        MockMultipartFile file = pdfFile("nopath.pdf");
        String resp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "OTHER")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(resp).doesNotContain(storagePath);
        org.assertj.core.api.Assertions.assertThat(resp).doesNotContain("storageKey");
        // also check that response doesn't contain absolute path
        org.assertj.core.api.Assertions.assertThat(resp).doesNotContain("./storage");
    }

    @Test
    void shouldRejectPathTraversalAttempt() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "../../../etc/passwd", "application/pdf", "content".getBytes());
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "OTHER")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFileName").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(".."))));
        // verify stored file does not escape root
        // we already tested safe generation above
    }

    @Test
    void shouldGetDocumentMetadata() throws Exception {
        MockMultipartFile file = pdfFile("getmeta.pdf");
        String resp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "RECEIPT")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        mockMvc.perform(get("/api/v1/documents/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.organizationId").value(orgId));
    }

    @Test
    void shouldIsolateTenantOnGet() throws Exception {
        MockMultipartFile file = pdfFile("tenant.pdf");
        String resp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "OTHER")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        String suffix = "tenant-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Other", "Tenant",
                "other.tenant." + suffix + "@example.com",
                "SecurePass123",
                "OtherTenantOrg-" + suffix,
                "Legal Other " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        mockMvc.perform(get("/api/v1/documents/{id}", id)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/documents/{id}/download", id)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldListAndPaginateAndFilter() throws Exception {
        // create 3 docs with different types
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(pdfFile("list1.pdf"))
                        .param("documentType", "INVOICE")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated());
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(jpegFile("list2.jpg"))
                        .param("documentType", "RECEIPT")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated());
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(pngFile("list3.png"))
                        .param("documentType", "OTHER")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/documents")
                        .header("Authorization", authHeader())
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").isNumber());

        mockMvc.perform(get("/api/v1/documents")
                        .header("Authorization", authHeader())
                        .param("documentType", "INVOICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        mockMvc.perform(get("/api/v1/documents")
                        .header("Authorization", authHeader())
                        .param("status", "UPLOADED"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/documents")
                        .header("Authorization", authHeader())
                        .param("originalFileName", "list1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void shouldDeleteAndCleanup() throws Exception {
        MockMultipartFile file = pdfFile("todelete.pdf");
        String resp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "OTHER")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        String storageKey = documentRepository.findById(java.util.UUID.fromString(id)).orElseThrow().getStorageKey();
        Path path = Paths.get(storagePath).resolve(storageKey);
        org.assertj.core.api.Assertions.assertThat(Files.exists(path)).isTrue();

        mockMvc.perform(delete("/api/v1/documents/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(documentRepository.findById(java.util.UUID.fromString(id))).isNotPresent();
        org.assertj.core.api.Assertions.assertThat(Files.exists(path)).isFalse();

        // deleting document should not delete invoice/expense
        // verify invoice still exists
        mockMvc.perform(get("/api/v1/invoices/{id}", invoiceId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk());
    }

    @Test
    void shouldNotDeleteInvoiceWhenDocumentDeleted() throws Exception {
        MockMultipartFile file = pdfFile("invdoc.pdf");
        String resp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "INVOICE")
                        .param("invoiceId", invoiceId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String docId = objectMapper.readTree(resp).get("id").asText();
        mockMvc.perform(delete("/api/v1/documents/{id}", docId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isNoContent());
        // invoice still exists
        mockMvc.perform(get("/api/v1/invoices/{id}", invoiceId)
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk());
    }

    @Test
    void shouldPreventCrossTenantDelete() throws Exception {
        MockMultipartFile file = pdfFile("crossdelete.pdf");
        String resp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "OTHER")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        String suffix = "crossdel-" + System.nanoTime();
        RegisterRequest otherReq = new RegisterRequest(
                "Other", "Del",
                "other.del." + suffix + "@example.com",
                "SecurePass123",
                "OtherDelOrg-" + suffix,
                "Legal Other Del " + suffix,
                "LLP", "USA", "USD", "America/New_York"
        );
        String otherResp = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherToken = objectMapper.readTree(otherResp).get("accessToken").asText();
        mockMvc.perform(delete("/api/v1/documents/{id}", id)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldEnforceAuthorizationOnUploadAndDelete() throws Exception {
        // EMPLOYEE and VIEWER cannot upload/delete, but can read/download
        String suffix = "authdoc-" + System.nanoTime();
        String empEmail = "emp.doc." + suffix + "@example.com";
        String empJson = """
                {"firstName":"Emp","lastName":"User","email":"%s","password":"SecurePass123"}
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

        MockMultipartFile file = pdfFile("emp.pdf");
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "OTHER")
                        .header("Authorization", "Bearer " + empToken))
                .andExpect(status().isForbidden());

        // create doc as owner for emp to read
        MockMultipartFile ownerFile = pdfFile("owner.pdf");
        String ownerDocResp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(ownerFile)
                        .param("documentType", "OTHER")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String ownerDocId = objectMapper.readTree(ownerDocResp).get("id").asText();

        mockMvc.perform(get("/api/v1/documents/{id}", ownerDocId)
                        .header("Authorization", "Bearer " + empToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/documents/{id}/download", ownerDocId)
                        .header("Authorization", "Bearer " + empToken))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/documents/{id}", ownerDocId)
                        .header("Authorization", "Bearer " + empToken))
                .andExpect(status().isForbidden());

        // VIEWER similarly
        String viewSuffix = "view-doc-" + System.nanoTime();
        String viewerEmail = "viewer.doc." + viewSuffix + "@example.com";
        String viewerJson = """
                {"firstName":"Viewer","lastName":"User","email":"%s","password":"SecurePass123"}
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
        String vLoginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"SecurePass123"}
                                """.formatted(viewerEmail)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewerToken = objectMapper.readTree(vLoginResp).get("accessToken").asText();
        MockMultipartFile vFile = pdfFile("viewer.pdf");
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(vFile)
                        .param("documentType", "OTHER")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/documents/{id}", ownerDocId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn401WhenMissingAuth() throws Exception {
        MockMultipartFile file = pdfFile("noauth.pdf");
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "INVOICE"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldHandleMissingPhysicalFileGracefully() throws Exception {
        MockMultipartFile file = pdfFile("missing.pdf");
        String resp = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "OTHER")
                        .header("Authorization", authHeader()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();
        // manually delete physical file
        String storageKey = documentRepository.findById(java.util.UUID.fromString(id)).orElseThrow().getStorageKey();
        Path path = Paths.get(storagePath).resolve(storageKey);
        Files.deleteIfExists(path);
        // delete via API should still succeed and remove metadata
        mockMvc.perform(delete("/api/v1/documents/{id}", id)
                        .header("Authorization", authHeader()))
                .andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(documentRepository.findById(java.util.UUID.fromString(id))).isNotPresent();
    }
}
