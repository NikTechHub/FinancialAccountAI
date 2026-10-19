package com.example.accounting.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record GeneralLedgerLine(
        UUID journalEntryId,
        String entryNumber,
        LocalDate entryDate,
        String description,
        String reference,
        UUID accountId,
        String accountCode,
        String accountName,
        String lineDescription,
        BigDecimal debitAmount,
        BigDecimal creditAmount
) {
}
