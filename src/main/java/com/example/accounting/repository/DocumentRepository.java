package com.example.accounting.repository;

import com.example.accounting.entity.Document;
import com.example.accounting.entity.DocumentStatus;
import com.example.accounting.entity.DocumentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("SELECT d FROM Document d WHERE d.organization.id IN :orgIds")
    Page<Document> findByOrganizationIdIn(@Param("orgIds") List<UUID> orgIds, Pageable pageable);

    @Query("SELECT d FROM Document d WHERE d.organization.id IN :orgIds AND d.status = :status")
    Page<Document> findByOrganizationIdInAndStatus(@Param("orgIds") List<UUID> orgIds, @Param("status") DocumentStatus status, Pageable pageable);

    @Query("SELECT d FROM Document d WHERE d.organization.id IN :orgIds AND d.documentType = :type")
    Page<Document> findByOrganizationIdInAndDocumentType(@Param("orgIds") List<UUID> orgIds, @Param("type") DocumentType type, Pageable pageable);

    @Query("SELECT d FROM Document d WHERE d.organization.id IN :orgIds AND d.invoice.id = :invoiceId")
    Page<Document> findByOrganizationIdInAndInvoiceId(@Param("orgIds") List<UUID> orgIds, @Param("invoiceId") UUID invoiceId, Pageable pageable);

    @Query("SELECT d FROM Document d WHERE d.organization.id IN :orgIds AND d.expense.id = :expenseId")
    Page<Document> findByOrganizationIdInAndExpenseId(@Param("orgIds") List<UUID> orgIds, @Param("expenseId") UUID expenseId, Pageable pageable);

    @Query("SELECT d FROM Document d WHERE d.organization.id IN :orgIds AND LOWER(d.originalFileName) LIKE LOWER(CONCAT('%', :filename, '%'))")
    Page<Document> findByOrganizationIdInAndOriginalFileNameContainingIgnoreCase(@Param("orgIds") List<UUID> orgIds, @Param("filename") String filename, Pageable pageable);

    List<Document> findByOrganizationId(UUID organizationId);
}
