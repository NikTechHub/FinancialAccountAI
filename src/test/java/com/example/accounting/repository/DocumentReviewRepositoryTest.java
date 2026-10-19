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
class DocumentReviewRepositoryTest {

    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentExtractionRepository extractionRepository;
    @Autowired private DocumentReviewRepository reviewRepository;

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

    private DocumentExtraction createExtraction(Document doc, Organization org) {
        DocumentExtraction ex = new DocumentExtraction(doc, org, DocumentType.INVOICE);
        ex.setStatus(DocumentExtractionStatus.COMPLETED);
        ex.setStructuredData("{\"invoiceNumber\":\"INV-001\"}");
        ex.setModel("fake-model");
        ex.setPromptVersion("invoice-extraction-v1");
        return extractionRepository.save(ex);
    }

    @Test
    void shouldPersistReview() {
        Organization org = createOrg("persist");
        User user = createUser("persist");
        Document doc = createDoc(org, user);
        DocumentExtraction ex = createExtraction(doc, org);
        DocumentReview review = new DocumentReview(org, doc, ex, "{\"totalAmount\":100}");
        review.setStatus(DocumentReviewStatus.PENDING);
        DocumentReview saved = reviewRepository.save(review);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(DocumentReviewStatus.PENDING);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldEnforceUniqueExtractionId() {
        Organization org = createOrg("unique");
        User user = createUser("unique");
        Document doc = createDoc(org, user);
        DocumentExtraction ex = createExtraction(doc, org);
        DocumentReview r1 = new DocumentReview(org, doc, ex, "{}");
        reviewRepository.saveAndFlush(r1);
        DocumentReview r2 = new DocumentReview(org, doc, ex, "{}");
        try {
            reviewRepository.saveAndFlush(r2);
            assertThat(false).as("Expected unique violation").isTrue();
        } catch (Exception e) {
            assertThat(e).isNotNull();
        }
    }

    @Test
    void shouldFindByDocumentId() {
        Organization org = createOrg("find");
        User user = createUser("find");
        Document doc = createDoc(org, user);
        DocumentExtraction ex = createExtraction(doc, org);
        DocumentReview review = new DocumentReview(org, doc, ex, "{}");
        reviewRepository.save(review);
        assertThat(reviewRepository.findByDocumentId(doc.getId())).isPresent();
        assertThat(reviewRepository.findByDocumentId(UUID.randomUUID())).isNotPresent();
    }

    @Test
    void shouldFindByExtractionId() {
        Organization org = createOrg("ext");
        User user = createUser("ext");
        Document doc = createDoc(org, user);
        DocumentExtraction ex = createExtraction(doc, org);
        DocumentReview review = new DocumentReview(org, doc, ex, "{}");
        reviewRepository.save(review);
        assertThat(reviewRepository.findByExtractionId(ex.getId())).isPresent();
    }

    @Test
    void shouldPersistReviewerAndStatus() {
        Organization org = createOrg("rev");
        User user = createUser("rev");
        Document doc = createDoc(org, user);
        DocumentExtraction ex = createExtraction(doc, org);
        DocumentReview review = new DocumentReview(org, doc, ex, "{}");
        review.setStatus(DocumentReviewStatus.APPROVED);
        review.setReviewedBy(user);
        review.setReviewedAt(java.time.Instant.now());
        review.setReviewComment("Looks good");
        DocumentReview saved = reviewRepository.save(review);
        assertThat(saved.getStatus()).isEqualTo(DocumentReviewStatus.APPROVED);
        assertThat(saved.getReviewedBy().getId()).isEqualTo(user.getId());
        assertThat(saved.getReviewComment()).isEqualTo("Looks good");
    }

    @Test
    void shouldFindByDocumentAndOrganization() {
        Organization org = createOrg("tenant");
        User user = createUser("tenant");
        Document doc = createDoc(org, user);
        DocumentExtraction ex = createExtraction(doc, org);
        DocumentReview review = new DocumentReview(org, doc, ex, "{}");
        reviewRepository.save(review);
        assertThat(reviewRepository.findByDocumentIdAndOrganizationId(doc.getId(), org.getId())).isPresent();
        assertThat(reviewRepository.findByDocumentIdAndOrganizationId(doc.getId(), UUID.randomUUID())).isNotPresent();
    }
}
