package com.example.accounting.controller;

import com.example.accounting.dto.CreateExpenseRequest;
import com.example.accounting.dto.ExpenseResponse;
import com.example.accounting.dto.UpdateExpenseRequest;
import com.example.accounting.entity.ExpenseStatus;
import com.example.accounting.security.AuthContext;
import com.example.accounting.service.ExpenseService;
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
@RequestMapping("/api/v1/expenses")
public class ExpenseController {

    private final ExpenseService expenseService;

    public ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    @PostMapping
    public ResponseEntity<ExpenseResponse> create(@Valid @RequestBody CreateExpenseRequest request) {
        UUID userId = AuthContext.currentUserId();
        ExpenseResponse response = expenseService.createDraft(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ExpenseResponse> get(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        ExpenseResponse response = expenseService.getExpense(userId, id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<Page<ExpenseResponse>> list(
            @RequestParam(required = false) ExpenseStatus status,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) UUID expenseAccountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expenseDate,
            @RequestParam(required = false) String reference,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID userId = AuthContext.currentUserId();
        Page<ExpenseResponse> page = expenseService.listExpenses(userId, status, vendorId, expenseAccountId, expenseDate, reference, pageable);
        return ResponseEntity.ok(page);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ExpenseResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateExpenseRequest request) {
        UUID userId = AuthContext.currentUserId();
        ExpenseResponse response = expenseService.updateDraft(userId, id, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/record")
    public ResponseEntity<ExpenseResponse> record(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        ExpenseResponse response = expenseService.recordExpense(userId, id);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ExpenseResponse> voidExpense(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        ExpenseResponse response = expenseService.voidDraft(userId, id);
        return ResponseEntity.ok(response);
    }
}
