package com.example.accounting.repository;

import com.example.accounting.entity.OcrResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OcrResultRepository extends JpaRepository<OcrResult, UUID> {

    Optional<OcrResult> findByDocumentId(UUID documentId);

    Optional<OcrResult> findByDocumentIdAndDocumentOrganizationId(UUID documentId, UUID organizationId);

    boolean existsByDocumentId(UUID documentId);
}
