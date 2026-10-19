package com.example.accounting.assistant;

import java.time.LocalDate;

public record ResolvedPeriod(
        LocalDate fromDate,
        LocalDate toDate,
        String label
) {
    public long lengthInDays() {
        return toDate.toEpochDay() - fromDate.toEpochDay() + 1;
    }
}
