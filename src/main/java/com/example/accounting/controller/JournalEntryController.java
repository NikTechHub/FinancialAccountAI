package com.example.accounting.controller;

import com.example.accounting.dto.CreateJournalEntryRequest;
import com.example.accounting.dto.JournalEntryResponse;
import com.example.accounting.dto.UpdateJournalEntryRequest;
import com.example.accounting.entity.JournalEntryStatus;
import com.example.accounting.security.AuthContext;
import com.example.accounting.service.JournalEntryService;
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
@RequestMapping("/api/v1/journal-entries")
public class JournalEntryController {

    private final JournalEntryService journalEntryService;

    public JournalEntryController(JournalEntryService journalEntryService) {
        this.journalEntryService = journalEntryService;
    }

    @PostMapping
    public ResponseEntity<JournalEntryResponse> create(@Valid @RequestBody CreateJournalEntryRequest request) {
        UUID userId = AuthContext.currentUserId();
        JournalEntryResponse response = journalEntryService.createDraft(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<JournalEntryResponse> get(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        JournalEntryResponse response = journalEntryService.getJournalEntry(userId, id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<Page<JournalEntryResponse>> list(
            @RequestParam(required = false) JournalEntryStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate entryDate,
            @RequestParam(required = false) String entryNumber,
            @RequestParam(required = false) String reference,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID userId = AuthContext.currentUserId();
        Page<JournalEntryResponse> page = journalEntryService.listJournalEntries(userId, status, entryDate, entryNumber, reference, pageable);
        return ResponseEntity.ok(page);
    }

    @PutMapping("/{id}")
    public ResponseEntity<JournalEntryResponse> update(@PathVariable UUID id,
                                                       @Valid @RequestBody UpdateJournalEntryRequest request) {
        UUID userId = AuthContext.currentUserId();
        JournalEntryResponse response = journalEntryService.updateDraft(userId, id, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/post")
    public ResponseEntity<JournalEntryResponse> postEntry(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        JournalEntryResponse response = journalEntryService.postJournalEntry(userId, id);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<JournalEntryResponse> voidEntry(@PathVariable UUID id) {
        UUID userId = AuthContext.currentUserId();
        JournalEntryResponse response = journalEntryService.voidJournalEntry(userId, id);
        return ResponseEntity.ok(response);
    }
}
