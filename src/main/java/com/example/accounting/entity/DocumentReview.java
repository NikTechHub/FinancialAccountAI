package com.example.accounting.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_reviews", uniqueConstraints = {
        @UniqueConstraint(name = "uk_review_extraction", columnNames = {"extraction_id"})
}, indexes = {
        @Index(name = "idx_reviews_organization_id", columnList = "organization_id"),
        @Index(name = "idx_reviews_document_id", columnList = "document_id"),
        @Index(name = "idx_reviews_extraction_id", columnList = "extraction_id"),
        @Index(name = "idx_reviews_status", columnList = "status")
})
@EntityListeners(AuditingEntityListener.class)
public class DocumentReview {

    @Id
    @UuidGenerator
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", columnDefinition = "CHAR(36)", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "extraction_id", nullable = false, unique = true)
    private DocumentExtraction extraction;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private DocumentReviewStatus status = DocumentReviewStatus.PENDING;

    @Column(name = "reviewed_data", columnDefinition = "LONGTEXT")
    private String reviewedData;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_comment", length = 2000)
    private String reviewComment;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    protected DocumentReview() {}

    public DocumentReview(Organization organization, Document document, DocumentExtraction extraction, String reviewedData) {
        this.organization = organization;
        this.document = document;
        this.extraction = extraction;
        this.reviewedData = reviewedData;
        this.status = DocumentReviewStatus.PENDING;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Organization getOrganization() { return organization; }
    public void setOrganization(Organization organization) { this.organization = organization; }
    public Document getDocument() { return document; }
    public void setDocument(Document document) { this.document = document; }
    public DocumentExtraction getExtraction() { return extraction; }
    public void setExtraction(DocumentExtraction extraction) { this.extraction = extraction; }
    public DocumentReviewStatus getStatus() { return status; }
    public void setStatus(DocumentReviewStatus status) { this.status = status; }
    public String getReviewedData() { return reviewedData; }
    public void setReviewedData(String reviewedData) { this.reviewedData = reviewedData; }
    public User getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(User reviewedBy) { this.reviewedBy = reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
