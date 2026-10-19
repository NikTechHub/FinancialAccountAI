package com.example.accounting.repository;

import com.example.accounting.config.JpaAuditingConfig;
import com.example.accounting.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
class OcrResultRepositoryTest {

    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private OcrResultRepository ocrResultRepository;

    private Organization createOrg(String suffix) {
        return organizationRepository.save(new Organization("Org-" + suffix, "Legal-" + suffix, "LLP", "India", "INR", "Asia/Kolkata"));
    }

    private User createUser(String suffix) {
        return userRepository.save(new User("First" + suffix, "Last" + suffix, "user" + suffix + "@example.com", "hash", true));
    }

    private Document createDoc(Organization org, User user) {
        Document doc = new Document(org, null, null, "test.pdf", "stored.pdf",
                "organizations/" + org.getId() + "/documents/" + UUID.randomUUID() + "/file.pdf",
                "application/pdf", 100L, DocumentType.INVOICE, "checksum", user);
        return documentRepository.save(doc);
    }

    @Test
    void shouldPersistOcrResult() {
        Organization org = createOrg("persist");
        User user = createUser("persist");
        Document doc = createDoc(org, user);
        OcrResult result = new OcrResult(doc, OcrStatus.COMPLETED);
        result.setExtractedText("Sample extracted text");
        result.setPageCount(2);
        OcrResult saved = ocrResultRepository.save(result);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getExtractedText()).isEqualTo("Sample extracted text");
        assertThat(saved.getPageCount()).isEqualTo(2);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldEnforceUniqueDocumentId() {
        Organization org = createOrg("unique");
        User user = createUser("unique");
        Document doc = createDoc(org, user);
        OcrResult r1 = new OcrResult(doc, OcrStatus.COMPLETED);
        r1.setExtractedText("text1");
        ocrResultRepository.saveAndFlush(r1);
        OcrResult r2 = new OcrResult(doc, OcrStatus.FAILED);
        r2.setExtractedText("text2");
        try {
            ocrResultRepository.saveAndFlush(r2);
            assertThat(false).as("Expected unique violation").isTrue();
        } catch (Exception ex) {
            assertThat(ex).isNotNull();
        }
    }

    @Test
    void shouldUpdateStatusLifecycle() {
        Organization org = createOrg("lifecycle");
        User user = createUser("lifecycle");
        Document doc = createDoc(org, user);
        OcrResult result = new OcrResult(doc, OcrStatus.PENDING);
        result = ocrResultRepository.save(result);
        assertThat(result.getStatus()).isEqualTo(OcrStatus.PENDING);
        result.setStatus(OcrStatus.PROCESSING);
        result.setProcessingStartedAt(java.time.Instant.now());
        result = ocrResultRepository.save(result);
        assertThat(result.getStatus()).isEqualTo(OcrStatus.PROCESSING);
        result.setStatus(OcrStatus.COMPLETED);
        result.setExtractedText("done");
        result.setPageCount(1);
        result.setProcessingCompletedAt(java.time.Instant.now());
        result = ocrResultRepository.save(result);
        assertThat(result.getStatus()).isEqualTo(OcrStatus.COMPLETED);
        assertThat(result.getProcessingCompletedAt()).isNotNull();
    }

    @Test
    void shouldPersistErrorMessage() {
        Organization org = createOrg("error");
        User user = createUser("error");
        Document doc = createDoc(org, user);
        OcrResult result = new OcrResult(doc, OcrStatus.FAILED);
        result.setErrorMessage("Simulated OCR failure");
        result.setProcessingStartedAt(java.time.Instant.now());
        result.setProcessingCompletedAt(java.time.Instant.now());
        OcrResult saved = ocrResultRepository.save(result);
        assertThat(saved.getErrorMessage()).isEqualTo("Simulated OCR failure");
    }

    @Test
    void shouldFindByDocumentId() {
        Organization org = createOrg("find");
        User user = createUser("find");
        Document doc = createDoc(org, user);
        OcrResult result = new OcrResult(doc, OcrStatus.COMPLETED);
        result.setExtractedText("text");
        ocrResultRepository.save(result);
        assertThat(ocrResultRepository.findByDocumentId(doc.getId())).isPresent();
        assertThat(ocrResultRepository.findByDocumentId(UUID.randomUUID())).isNotPresent();
    }
}
