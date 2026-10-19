package com.example.accounting.repository;

import com.example.accounting.entity.Expense;
import com.example.accounting.entity.ExpenseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

    Optional<Expense> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("SELECT e FROM Expense e WHERE e.organization.id IN :orgIds")
    Page<Expense> findByOrganizationIdIn(@Param("orgIds") List<UUID> orgIds, Pageable pageable);

    @Query("SELECT e FROM Expense e WHERE e.organization.id IN :orgIds AND e.status = :status")
    Page<Expense> findByOrganizationIdInAndStatus(@Param("orgIds") List<UUID> orgIds,
                                                  @Param("status") ExpenseStatus status,
                                                  Pageable pageable);

    @Query("SELECT e FROM Expense e WHERE e.organization.id IN :orgIds AND e.vendor.id = :vendorId")
    Page<Expense> findByOrganizationIdInAndVendorId(@Param("orgIds") List<UUID> orgIds,
                                                    @Param("vendorId") UUID vendorId,
                                                    Pageable pageable);

    @Query("SELECT e FROM Expense e WHERE e.organization.id IN :orgIds AND e.expenseAccount.id = :accountId")
    Page<Expense> findByOrganizationIdInAndExpenseAccountId(@Param("orgIds") List<UUID> orgIds,
                                                            @Param("accountId") UUID accountId,
                                                            Pageable pageable);

    @Query("SELECT e FROM Expense e WHERE e.organization.id IN :orgIds AND e.expenseDate = :date")
    Page<Expense> findByOrganizationIdInAndExpenseDate(@Param("orgIds") List<UUID> orgIds,
                                                       @Param("date") LocalDate date,
                                                       Pageable pageable);

    @Query("SELECT e FROM Expense e WHERE e.organization.id IN :orgIds AND " +
            "(LOWER(e.reference) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(e.description) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Expense> searchByReferenceOrDescription(@Param("orgIds") List<UUID> orgIds,
                                                 @Param("search") String search,
                                                 Pageable pageable);

    List<Expense> findByOrganizationId(UUID organizationId);
}
