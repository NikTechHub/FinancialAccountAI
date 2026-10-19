package com.example.accounting.repository;

import com.example.accounting.entity.Invoice;
import com.example.accounting.entity.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    boolean existsByOrganizationIdAndInvoiceNumber(UUID organizationId, String invoiceNumber);

    Optional<Invoice> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("SELECT i FROM Invoice i WHERE i.organization.id IN :orgIds")
    Page<Invoice> findByOrganizationIdIn(@Param("orgIds") List<UUID> orgIds, Pageable pageable);

    @Query("SELECT i FROM Invoice i WHERE i.organization.id IN :orgIds AND i.status = :status")
    Page<Invoice> findByOrganizationIdInAndStatus(@Param("orgIds") List<UUID> orgIds,
                                                  @Param("status") InvoiceStatus status,
                                                  Pageable pageable);

    @Query("SELECT i FROM Invoice i WHERE i.organization.id IN :orgIds AND i.customer.id = :customerId")
    Page<Invoice> findByOrganizationIdInAndCustomerId(@Param("orgIds") List<UUID> orgIds,
                                                      @Param("customerId") UUID customerId,
                                                      Pageable pageable);

    @Query("SELECT i FROM Invoice i WHERE i.organization.id IN :orgIds AND i.invoiceDate = :invoiceDate")
    Page<Invoice> findByOrganizationIdInAndInvoiceDate(@Param("orgIds") List<UUID> orgIds,
                                                       @Param("invoiceDate") LocalDate invoiceDate,
                                                       Pageable pageable);

    @Query("SELECT i FROM Invoice i WHERE i.organization.id IN :orgIds AND i.dueDate = :dueDate")
    Page<Invoice> findByOrganizationIdInAndDueDate(@Param("orgIds") List<UUID> orgIds,
                                                   @Param("dueDate") LocalDate dueDate,
                                                   Pageable pageable);

    @Query("SELECT i FROM Invoice i WHERE i.organization.id IN :orgIds AND LOWER(i.invoiceNumber) LIKE LOWER(CONCAT('%', :invoiceNumber, '%'))")
    Page<Invoice> findByOrganizationIdInAndInvoiceNumberContainingIgnoreCase(@Param("orgIds") List<UUID> orgIds,
                                                                             @Param("invoiceNumber") String invoiceNumber,
                                                                             Pageable pageable);

    List<Invoice> findByOrganizationId(UUID organizationId);
}
