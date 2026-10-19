package com.example.accounting.controller;

import com.example.accounting.dto.CreateInvoiceRequest;
import com.example.accounting.dto.InvoiceResponse;
import com.example.accounting.dto.UpdateInvoiceRequest;
import com.example.accounting.entity.InvoiceStatus;
import com.example.accounting.security.AuthContext;
import com.example.accounting.service.InvoiceService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @PostMapping
    public ResponseEntity<InvoiceResponse> create(@Valid @RequestBody CreateInvoiceRequest request) {
        UUID userId = AuthContext.currentUserId();
        InvoiceResponse response = invoiceService.createInvoice(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvoiceResponse> get(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        InvoiceResponse response = invoiceService.getInvoice(userId, id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<Page<InvoiceResponse>> list(
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String invoiceNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate invoiceDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID userId = AuthContext.currentUserId();
        // For simplicity, handle one filter at a time prioritized: status, customer, invoiceNumber, invoiceDate, dueDate
        // Service handles filtering; we delegate with all params where service currently handles status/customer/invoiceNumber/invoiceDate/dueDate
        // But our service's list method currently takes status, customerId, invoiceNumber, invoiceDate, dueDate
        // We need to match service signature: listInvoices(userId, status, customerId, invoiceNumber, invoiceDate, dueDate, pageable)
        // Our current service has only status, customerId, invoiceNumber, invoiceDate, dueDate? Let's check.
        // Actually we implemented listInvoices with status, customerId, invoiceNumber, invoiceDate, dueDate
        // We will call accordingly.
        Page<InvoiceResponse> page = invoiceService.listInvoices(userId, status, customerId, invoiceNumber, invoiceDate, dueDate, pageable);
        return ResponseEntity.ok(page);
    }

    @PutMapping("/{id}")
    public ResponseEntity<InvoiceResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateInvoiceRequest request) {
        UUID userId = AuthContext.currentUserId();
        InvoiceResponse response = invoiceService.updateInvoice(userId, id, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/issue")
    public ResponseEntity<InvoiceResponse> issue(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        InvoiceResponse response = invoiceService.issueInvoice(userId, id);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<InvoiceResponse> voidInvoice(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        InvoiceResponse response = invoiceService.voidInvoice(userId, id);
        return ResponseEntity.ok(response);
    }
}
