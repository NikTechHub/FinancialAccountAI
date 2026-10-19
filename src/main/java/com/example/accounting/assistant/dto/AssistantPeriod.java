package com.example.accounting.assistant.dto;

import java.time.LocalDate;

public record AssistantPeriod(
        LocalDate fromDate,
        LocalDate toDate
) {
}
