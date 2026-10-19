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
class DocumentExtractionRepositoryTest {

    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private DocumentExtractionRepository extractionRepository;

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
    void shouldPersistExtraction() {
        Organization org = createOrg("persist");
        User user = createUser("persist");
        Document doc = createDoc(org, user);
        DocumentExtraction extraction = new DocumentExtraction(doc, org, DocumentType.INVOICE);
        extraction.setStatus(DocumentExtractionStatus.COMPLETED);
        extraction.setModel("test-model");
        extraction.setPromptVersion("v1");
        extraction.setRawResponse("{\"raw\":1}");
        extraction.setStructuredData("{\"invoiceNumber\":\"INV-001\"}");
        extraction.setConfidence(new java.math.BigDecimal("0.95"));
        DocumentExtraction saved = extractionRepository.save(extraction);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(DocumentExtractionStatus.COMPLETED);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldEnforceUniqueDocumentId() {
        Organization org = createOrg("unique");
        User user = createUser("unique");
        Document doc = createDoc(org, user);
        DocumentExtraction e1 = new DocumentExtraction(doc, org, DocumentType.INVOICE);
        e1.setStatus(DocumentExtractionStatus.COMPLETED);
        extractionRepository.saveAndFlush(e1);
        DocumentExtraction e2 = new DocumentExtraction(doc, org, DocumentType.INVOICE);
        e2.setStatus(DocumentExtractionStatus.FAILED);
        try {
            extractionRepository.saveAndFlush(e2);
            assertThat(false).as("Expected unique violation").isTrue();
        } catch (Exception ex) {
            assertThat(ex).isNotNull();
        }
    }

    @Test
    void shouldFindByDocumentId() {
        Organization org = createOrg("find");
        User user = createUser("find");
        Document doc = createDoc(org, user);
        DocumentExtraction extraction = new DocumentExtraction(doc, org, DocumentType.INVOICE);
        extraction.setStatus(DocumentExtractionStatus.PENDING);
        extractionRepository.save(extraction);
        assertThat(extractionRepository.findByDocumentId(doc.getId())).isPresent();
        assertThat(extractionRepository.findByDocumentId(UUID.randomUUID())).isNotPresent();
    }

    @Test
    void shouldUpdateStatusLifecycle() {
        Organization org = createOrg("lifecycle");
        User user = createUser("lifecycle");
        Document doc = createDoc(org, user);
        DocumentExtraction extraction = new DocumentExtraction(doc, org, DocumentType.INVOICE);
        extraction.setStatus(DocumentExtractionStatus.PENDING);
        extraction = extractionRepository.save(extraction);
        assertThat(extraction.getStatus()).isEqualTo(DocumentExtractionStatus.PENDING);
        extraction.setStatus(DocumentExtractionStatus.PROCESSING);
        extraction = extractionRepository.save(extraction);
        assertThat(extraction.getStatus()).isEqualTo(DocumentExtractionStatus.PROCESSING);
        extraction.setStatus(DocumentExtractionStatus.COMPLETED);
        extraction.setStructuredData("{\"total\":100}");
        extraction = extractionRepository.save(extraction);
        assertThat(extraction.getStatus()).isEqualTo(DocumentExtractionStatus.COMPLETED);
    }

    @Test
    void shouldFindByDocumentAndOrganization() {
        Organization org = createOrg("tenant");
        User user = createUser("tenant");
        Document doc = createDoc(org, user);
        DocumentExtraction extraction = new DocumentExtraction(doc, org, DocumentType.INVOICE);
        extraction.setStatus(DocumentExtractionStatus.COMPLETED);
        extractionRepository.save(extraction);
        assertThat(extractionRepository.findByDocumentIdAndOrganizationId(doc.getId(), org.getId())).isPresent();
        assertThat(extractionRepository.findByDocumentIdAndOrganizationId(doc.getId(), UUID.randomUUID())).isNotPresent();
    }
}
