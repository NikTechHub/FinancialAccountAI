package com.example.accounting.controller;

import com.example.accounting.dto.CreateOrganizationRequest;
import com.example.accounting.dto.OrganizationResponse;
import com.example.accounting.entity.MembershipRole;
import com.example.accounting.security.AuthContext;
import com.example.accounting.security.OrganizationAccessService;
import com.example.accounting.service.OrganizationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final OrganizationService organizationService;
    private final OrganizationAccessService organizationAccessService;

    public OrganizationController(OrganizationService organizationService,
                                OrganizationAccessService organizationAccessService) {
        this.organizationService = organizationService;
        this.organizationAccessService = organizationAccessService;
    }

    @PostMapping
    public ResponseEntity<OrganizationResponse> createOrganization(@Valid @RequestBody CreateOrganizationRequest request) {
        UUID userId = AuthContext.currentUserId();
        // any authenticated user can create org; they become OWNER via service
        OrganizationResponse response = organizationService.createOrganization(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrganizationResponse> getOrganization(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        organizationAccessService.requireMembership(userId, id);
        OrganizationResponse response = organizationService.getOrganizationById(id);
        return ResponseEntity.ok(response);
    }
}
