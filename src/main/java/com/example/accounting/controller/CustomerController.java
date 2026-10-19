package com.example.accounting.controller;

import com.example.accounting.dto.CreateCustomerRequest;
import com.example.accounting.dto.CustomerResponse;
import com.example.accounting.dto.UpdateCustomerRequest;
import com.example.accounting.security.AuthContext;
import com.example.accounting.security.OrganizationAccessService;
import com.example.accounting.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService customerService;
    private final OrganizationAccessService organizationAccessService;

    public CustomerController(CustomerService customerService,
                              OrganizationAccessService organizationAccessService) {
        this.customerService = customerService;
        this.organizationAccessService = organizationAccessService;
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> createCustomer(@Valid @RequestBody CreateCustomerRequest request) {
        UUID userId = AuthContext.currentUserId();
        // basic membership check - detailed role check inside service
        var orgIds = organizationAccessService.getOrganizationIds(userId);
        if (orgIds.isEmpty()) {
            throw new org.springframework.security.access.AccessDeniedException("No organization membership");
        }
        CustomerResponse response = customerService.createCustomer(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerResponse> getCustomer(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        CustomerResponse response = customerService.getCustomer(userId, id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<Page<CustomerResponse>> listCustomers(
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID userId = AuthContext.currentUserId();
        Page<CustomerResponse> page = customerService.listCustomers(userId, search, pageable);
        return ResponseEntity.ok(page);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CustomerResponse> updateCustomer(@PathVariable UUID id,
                                                           @Valid @RequestBody UpdateCustomerRequest request) {
        UUID userId = AuthContext.currentUserId();
        CustomerResponse response = customerService.updateCustomer(userId, id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCustomer(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        customerService.deactivateCustomer(userId, id);
        return ResponseEntity.noContent().build();
    }
}
