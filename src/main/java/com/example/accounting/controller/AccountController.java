package com.example.accounting.controller;

import com.example.accounting.dto.AccountResponse;
import com.example.accounting.dto.AccountTreeResponse;
import com.example.accounting.dto.CreateAccountRequest;
import com.example.accounting.dto.UpdateAccountRequest;
import com.example.accounting.entity.AccountType;
import com.example.accounting.security.AuthContext;
import com.example.accounting.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        UUID userId = AuthContext.currentUserId();
        AccountResponse response = accountService.createAccount(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        AccountResponse response = accountService.getAccount(userId, id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<Page<AccountResponse>> listAccounts(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) AccountType type,
            @RequestParam(required = false) UUID parentAccountId,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID userId = AuthContext.currentUserId();
        Page<AccountResponse> page = accountService.listAccounts(userId, search, type, parentAccountId, pageable);
        return ResponseEntity.ok(page);
    }

    @GetMapping("/tree")
    public ResponseEntity<List<AccountTreeResponse>> getTree() {
        UUID userId = AuthContext.currentUserId();
        List<AccountTreeResponse> tree = accountService.getTree(userId);
        return ResponseEntity.ok(tree);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AccountResponse> updateAccount(@PathVariable UUID id,
                                                         @Valid @RequestBody UpdateAccountRequest request) {
        UUID userId = AuthContext.currentUserId();
        AccountResponse response = accountService.updateAccount(userId, id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAccount(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        accountService.deactivateAccount(userId, id);
        return ResponseEntity.noContent().build();
    }
}
