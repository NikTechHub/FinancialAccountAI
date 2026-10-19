package com.example.accounting.controller;

import com.example.accounting.dto.CreateVendorRequest;
import com.example.accounting.dto.UpdateVendorRequest;
import com.example.accounting.dto.VendorResponse;
import com.example.accounting.entity.MembershipRole;
import com.example.accounting.security.AuthContext;
import com.example.accounting.security.OrganizationAccessService;
import com.example.accounting.service.VendorService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vendors")
public class VendorController {

    private final VendorService vendorService;
    private final OrganizationAccessService organizationAccessService;

    public VendorController(VendorService vendorService,
                            OrganizationAccessService organizationAccessService) {
        this.vendorService = vendorService;
        this.organizationAccessService = organizationAccessService;
    }

    @PostMapping
    public ResponseEntity<VendorResponse> createVendor(@Valid @RequestBody CreateVendorRequest request) {
        UUID userId = AuthContext.currentUserId();
        // Any member can create, but we still verify they belong to an org
        // Role restriction: VIEWER cannot create
        enforceCreateRole(userId);
        VendorResponse response = vendorService.createVendor(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<VendorResponse> getVendor(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        VendorResponse response = vendorService.getVendor(userId, id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<Page<VendorResponse>> listVendors(
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID userId = AuthContext.currentUserId();
        // any member can list
        Page<VendorResponse> page = vendorService.listVendors(userId, search, pageable);
        return ResponseEntity.ok(page);
    }

    @PutMapping("/{id}")
    public ResponseEntity<VendorResponse> updateVendor(@PathVariable UUID id,
                                                       @Valid @RequestBody UpdateVendorRequest request) {
        UUID userId = AuthContext.currentUserId();
        enforceUpdateRole(userId);
        VendorResponse response = vendorService.updateVendor(userId, id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVendor(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        enforceDeleteRole(userId);
        vendorService.deactivateVendor(userId, id);
        return ResponseEntity.noContent().build();
    }

    private void enforceCreateRole(UUID userId) {
        // Check if user has at least one org where they are OWNER/ADMIN/ACCOUNTANT/EMPLOYEE
        // Since vendors are created in primary org, check role there
        // For simplicity, allow any role except VIEWER to create
        // We need to fetch user's orgs and check if any allows create
        // Instead, we will let service resolve org and then check role via OrganizationAccessService if needed.
        // Here we do a broad check: if user is VIEWER in all orgs, deny. Otherwise allow.
        // To implement simply, we try to find an org where user has create privilege.
        // If none, we'll still let service create and later enforce via org check.
        // For now, we enforce by checking that user is not exclusively VIEWER.
        // This is a lightweight RBAC; detailed per-org check happens in service via accessible orgs.
        // We will fetch first org and check role.
        var orgIds = organizationAccessService.hasMembershipForAny(userId);
        if (orgIds.isEmpty()) {
            throw new org.springframework.security.access.AccessDeniedException("No organization membership");
        }
        // If all memberships are VIEWER, deny create/update/delete
        boolean canCreate = orgIds.stream()
                .anyMatch(orgId -> {
                    var roleOpt = organizationAccessService.userRole(userId, orgId);
                    return roleOpt.map(r -> r != MembershipRole.VIEWER).orElse(false);
                });
        if (!canCreate) {
            throw new org.springframework.security.access.AccessDeniedException("VIEWER role cannot create vendors");
        }
    }

    private void enforceUpdateRole(UUID userId) {
        enforceCreateRole(userId); // same as create
    }

    private void enforceDeleteRole(UUID userId) {
        // Only OWNER/ADMIN can deactivate
        var orgIds = organizationAccessService.hasMembershipForAny(userId);
        boolean canDelete = orgIds.stream()
                .anyMatch(orgId -> {
                    var roleOpt = organizationAccessService.userRole(userId, orgId);
                    return roleOpt.map(r -> r == MembershipRole.OWNER || r == MembershipRole.ADMIN).orElse(false);
                });
        if (!canDelete) {
            throw new org.springframework.security.access.AccessDeniedException("Only OWNER/ADMIN can deactivate vendors");
        }
    }
}
