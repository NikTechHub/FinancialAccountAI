package com.example.accounting.repository;

import com.example.accounting.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndName(UUID organizationId, String name);

    Page<Customer> findByOrganizationIdInAndActiveTrue(List<UUID> organizationIds, Pageable pageable);

    Page<Customer> findByOrganizationIdInAndNameContainingIgnoreCaseAndActiveTrue(
            List<UUID> organizationIds, String name, Pageable pageable);
}
