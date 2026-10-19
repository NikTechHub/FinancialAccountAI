package com.example.accounting.repository;

import com.example.accounting.entity.DocumentExtraction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentExtractionRepository extends JpaRepository<DocumentExtraction, UUID> {

    Optional<DocumentExtraction> findByDocumentId(UUID documentId);

    Optional<DocumentExtraction> findByDocumentIdAndOrganizationId(UUID documentId, UUID organizationId);

    boolean existsByDocumentId(UUID documentId);
}
