package com.example.accounting.repository;

import com.example.accounting.entity.Vendor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VendorRepository extends JpaRepository<Vendor, UUID> {

    Optional<Vendor> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndName(UUID organizationId, String name);

    Page<Vendor> findByOrganizationIdInAndActiveTrue(List<UUID> organizationIds, Pageable pageable);

    Page<Vendor> findByOrganizationIdInAndNameContainingIgnoreCaseAndActiveTrue(
            List<UUID> organizationIds, String name, Pageable pageable);

    List<Vendor> findByOrganizationId(UUID organizationId);
}
