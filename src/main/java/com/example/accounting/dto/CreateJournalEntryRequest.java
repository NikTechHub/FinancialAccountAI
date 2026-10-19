package com.example.accounting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record CreateJournalEntryRequest(
        @NotBlank(message = "entryNumber must not be blank")
        @Size(max = 50, message = "entryNumber must be at most 50 characters")
        String entryNumber,

        @NotNull(message = "entryDate must not be null")
        LocalDate entryDate,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @Size(max = 100, message = "reference must be at most 100 characters")
        String reference,

        @NotNull(message = "lines must not be null")
        @Size(min = 2, message = "journal entry must have at least 2 lines")
        @Valid
        List<JournalEntryLineRequest> lines
) {
}
