package com.example.accounting.controller;

import com.example.accounting.dto.CreateMembershipRequest;
import com.example.accounting.dto.MembershipResponse;
import com.example.accounting.entity.MembershipRole;
import com.example.accounting.security.AuthContext;
import com.example.accounting.security.OrganizationAccessService;
import com.example.accounting.service.MembershipService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/members")
public class MembershipController {

    private final MembershipService membershipService;
    private final OrganizationAccessService organizationAccessService;

    public MembershipController(MembershipService membershipService,
                              OrganizationAccessService organizationAccessService) {
        this.membershipService = membershipService;
        this.organizationAccessService = organizationAccessService;
    }

    @PostMapping
    public ResponseEntity<MembershipResponse> addMember(
            @PathVariable UUID organizationId,
            @Valid @RequestBody CreateMembershipRequest request) {
        UUID currentUserId = AuthContext.currentUserId();
        // Only OWNER or ADMIN can add members
        organizationAccessService.requireRole(currentUserId, organizationId, MembershipRole.OWNER, MembershipRole.ADMIN);
        MembershipResponse response = membershipService.addMember(organizationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<MembershipResponse>> listMembers(@PathVariable UUID organizationId) {
        UUID currentUserId = AuthContext.currentUserId();
        organizationAccessService.requireMembership(currentUserId, organizationId);
        List<MembershipResponse> members = membershipService.listMembers(organizationId);
        return ResponseEntity.ok(members);
    }
}
