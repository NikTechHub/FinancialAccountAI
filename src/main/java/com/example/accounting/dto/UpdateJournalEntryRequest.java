package com.example.accounting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record UpdateJournalEntryRequest(
        @Size(max = 50, message = "entryNumber must be at most 50 characters")
        String entryNumber,

        LocalDate entryDate,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @Size(max = 100, message = "reference must be at most 100 characters")
        String reference,

        @Valid
        List<JournalEntryLineRequest> lines
) {
}
