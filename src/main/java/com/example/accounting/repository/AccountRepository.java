package com.example.accounting.repository;

import com.example.accounting.entity.Account;
import com.example.accounting.entity.AccountType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);

    Optional<Account> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Account> findByOrganizationId(UUID organizationId);

    List<Account> findByOrganizationIdAndActiveTrue(UUID organizationId);

    @Query("SELECT a FROM Account a WHERE a.organization.id IN :orgIds AND a.active = true")
    Page<Account> findByOrganizationIdInAndActiveTrue(@Param("orgIds") List<UUID> organizationIds, Pageable pageable);

    @Query("SELECT a FROM Account a WHERE a.organization.id IN :orgIds AND a.active = true AND a.type = :type")
    Page<Account> findByOrganizationIdInAndTypeAndActiveTrue(@Param("orgIds") List<UUID> organizationIds,
                                                             @Param("type") AccountType type,
                                                             Pageable pageable);

    @Query("SELECT a FROM Account a WHERE a.organization.id IN :orgIds AND a.active = true AND a.parentAccount.id = :parentId")
    Page<Account> findByOrganizationIdInAndParentAccountIdAndActiveTrue(@Param("orgIds") List<UUID> organizationIds,
                                                                        @Param("parentId") UUID parentAccountId,
                                                                        Pageable pageable);

    @Query("SELECT a FROM Account a WHERE a.organization.id IN :orgIds AND a.active = true AND " +
            "(LOWER(a.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(a.code) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Account> searchByNameOrCode(@Param("orgIds") List<UUID> organizationIds,
                                     @Param("search") String search,
                                     Pageable pageable);

    @Query("SELECT a FROM Account a WHERE a.organization.id IN :orgIds AND a.active = true AND a.type = :type AND " +
            "(LOWER(a.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(a.code) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Account> searchByNameOrCodeAndType(@Param("orgIds") List<UUID> organizationIds,
                                            @Param("type") AccountType type,
                                            @Param("search") String search,
                                            Pageable pageable);

    List<Account> findByOrganizationIdInAndActiveTrueOrderByCodeAsc(List<UUID> organizationIds);

    List<Account> findByOrganizationIdAndParentAccountIsNullAndActiveTrue(UUID organizationId);

    List<Account> findByParentAccountIdAndActiveTrue(UUID parentAccountId);

    @Query("SELECT a FROM Account a WHERE a.organization.id IN :orgIds AND a.active = true ORDER BY a.code ASC")
    List<Account> findAllActiveOrdered(@Param("orgIds") List<UUID> orgIds);
}
