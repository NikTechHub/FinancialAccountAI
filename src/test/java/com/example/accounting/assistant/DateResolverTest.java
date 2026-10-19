package com.example.accounting.assistant;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DateResolverTest {

    private final DateResolver resolver = new DateResolver();
    // A Thursday.
    private final LocalDate today = LocalDate.of(2026, 10, 15);

    @Test
    void resolvesTodayAndYesterday() {
        assertThat(resolver.resolve("What happened today?", today))
                .contains(new ResolvedPeriod(today, today, "today"));
        assertThat(resolver.resolve("expenses yesterday", today))
                .contains(new ResolvedPeriod(today.minusDays(1), today.minusDays(1), "yesterday"));
    }

    @Test
    void resolvesWeeks() {
        assertThat(resolver.resolve("revenue this week", today))
                .contains(new ResolvedPeriod(
                        LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18), "this week"));
        assertThat(resolver.resolve("revenue last week", today))
                .contains(new ResolvedPeriod(
                        LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11), "last week"));
    }

    @Test
    void resolvesMonths() {
        assertThat(resolver.resolve("revenue this month", today))
                .contains(new ResolvedPeriod(
                        LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "this month"));
        assertThat(resolver.resolve("expenses last month", today))
                .contains(new ResolvedPeriod(
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "last month"));
    }

    @Test
    void resolvesQuartersAndYears() {
        assertThat(resolver.resolve("profit this quarter", today))
                .contains(new ResolvedPeriod(
                        LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31), "this quarter"));
        assertThat(resolver.resolve("profit last quarter", today))
                .contains(new ResolvedPeriod(
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), "last quarter"));
        assertThat(resolver.resolve("revenue this year", today))
                .contains(new ResolvedPeriod(
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "this year"));
        assertThat(resolver.resolve("revenue last year", today))
                .contains(new ResolvedPeriod(
                        LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), "last year"));
    }

    @Test
    void resolvesExplicitMonthAndYear() {
        assertThat(resolver.resolve("What was our net profit for September 2026?", today))
                .contains(new ResolvedPeriod(
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "September 2026"));
        assertThat(resolver.resolve("revenue in September", today))
                .contains(new ResolvedPeriod(
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "September 2026"));
    }

    @Test
    void resolvesExplicitRanges() {
        assertThat(resolver.resolve("revenue from 2026-01-01 to 2026-01-31", today))
                .contains(new ResolvedPeriod(
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "custom range"));
        assertThat(resolver.resolve("expenses January 1 to January 31", today))
                .contains(new ResolvedPeriod(
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "custom range"));
    }

    @Test
    void resolvesAsOfDates() {
        assertThat(resolver.resolveAsOf("Show me the balance sheet as of September 30.", today))
                .contains(LocalDate.of(2026, 9, 30));
        assertThat(resolver.resolveAsOf("balance sheet as of 2026-09-30", today))
                .contains(LocalDate.of(2026, 9, 30));
    }

    @Test
    void ambiguousOrInvalidDatesResolveEmpty() {
        assertThat(resolver.resolve("What was our revenue?", today)).isEmpty();
        assertThat(resolver.resolve("tell me about money", today)).isEmpty();
        assertThat(resolver.resolve(null, today)).isEmpty();
        assertThat(resolver.resolveAsOf("What are our assets?", today)).isEmpty();
    }
}
