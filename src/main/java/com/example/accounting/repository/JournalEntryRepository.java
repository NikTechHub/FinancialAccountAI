package com.example.accounting.repository;

import com.example.accounting.entity.JournalEntry;
import com.example.accounting.entity.JournalEntryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    boolean existsByOrganizationIdAndEntryNumber(UUID organizationId, String entryNumber);

    Optional<JournalEntry> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("SELECT j FROM JournalEntry j WHERE j.organization.id IN :orgIds")
    Page<JournalEntry> findByOrganizationIdIn(@Param("orgIds") List<UUID> orgIds, Pageable pageable);

    @Query("SELECT j FROM JournalEntry j WHERE j.organization.id IN :orgIds AND j.status = :status")
    Page<JournalEntry> findByOrganizationIdInAndStatus(@Param("orgIds") List<UUID> orgIds,
                                                       @Param("status") JournalEntryStatus status,
                                                       Pageable pageable);

    @Query("SELECT j FROM JournalEntry j WHERE j.organization.id IN :orgIds AND j.entryDate = :entryDate")
    Page<JournalEntry> findByOrganizationIdInAndEntryDate(@Param("orgIds") List<UUID> orgIds,
                                                          @Param("entryDate") LocalDate entryDate,
                                                          Pageable pageable);

    @Query("SELECT j FROM JournalEntry j WHERE j.organization.id IN :orgIds AND j.status = :status AND j.entryDate = :entryDate")
    Page<JournalEntry> findByOrganizationIdInAndStatusAndEntryDate(@Param("orgIds") List<UUID> orgIds,
                                                                   @Param("status") JournalEntryStatus status,
                                                                   @Param("entryDate") LocalDate entryDate,
                                                                   Pageable pageable);

    @Query("SELECT j FROM JournalEntry j WHERE j.organization.id IN :orgIds AND " +
            "(LOWER(j.entryNumber) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(j.reference) LIKE LOWER(CONCAT('%', :search, '%'))) ")
    Page<JournalEntry> searchByEntryNumberOrReference(@Param("orgIds") List<UUID> orgIds,
                                                      @Param("search") String search,
                                                      Pageable pageable);

    List<JournalEntry> findByOrganizationId(UUID organizationId);
}
