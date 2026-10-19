package com.example.accounting.repository;

import com.example.accounting.entity.DocumentReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentReviewRepository extends JpaRepository<DocumentReview, UUID> {

    Optional<DocumentReview> findByDocumentId(UUID documentId);

    Optional<DocumentReview> findByExtractionId(UUID extractionId);

    Optional<DocumentReview> findByDocumentIdAndOrganizationId(UUID documentId, UUID organizationId);

    boolean existsByExtractionId(UUID extractionId);
}
