package com.example.accounting.assistant;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic natural-language date resolution. The LLM is never asked
 * to compute dates; this class resolves common expressions against an
 * explicit reference date. Anything unrecognized yields
 * {@code Optional.empty()} so the caller asks for clarification instead of
 * inventing a period.
 */
@Component
public class DateResolver {

    private static final Pattern ISO_RANGE = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})\\s*(?:to|-|–|through|until)\\s*(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern ISO_SINGLE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern MONTH_DAY_RANGE = Pattern.compile(
            "([a-z]+)\\s+(\\d{1,2})(?:st|nd|rd|th)?\\s*(?:to|-|–|through|until)\\s*"
                    + "([a-z]+)?\\s*(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s*(\\d{4}))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTH_YEAR = Pattern.compile(
            "\\b(january|february|march|april|may|june|july|august|september|sept|october|november|december|"
                    + "jan|feb|mar|apr|jun|jul|aug|oct|nov|dec)\\s+(\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTH_ONLY = Pattern.compile(
            "\\b(january|february|march|april|may|june|july|august|september|october|november|december)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AS_OF_ISO = Pattern.compile(
            "as of\\s+(\\d{4}-\\d{2}-\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern AS_OF_MONTH_DAY = Pattern.compile(
            "as of\\s+([a-z]+)\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s*(\\d{4}))?",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, Month> MONTHS = Map.ofEntries(
            Map.entry("january", Month.JANUARY), Map.entry("jan", Month.JANUARY),
            Map.entry("february", Month.FEBRUARY), Map.entry("feb", Month.FEBRUARY),
            Map.entry("march", Month.MARCH), Map.entry("mar", Month.MARCH),
            Map.entry("april", Month.APRIL), Map.entry("apr", Month.APRIL),
            Map.entry("may", Month.MAY),
            Map.entry("june", Month.JUNE), Map.entry("jun", Month.JUNE),
            Map.entry("july", Month.JULY), Map.entry("jul", Month.JULY),
            Map.entry("august", Month.AUGUST), Map.entry("aug", Month.AUGUST),
            Map.entry("september", Month.SEPTEMBER), Map.entry("sept", Month.SEPTEMBER),
            Map.entry("october", Month.OCTOBER), Map.entry("oct", Month.OCTOBER),
            Map.entry("november", Month.NOVEMBER), Map.entry("nov", Month.NOVEMBER),
            Map.entry("december", Month.DECEMBER), Map.entry("dec", Month.DECEMBER));

    public Optional<ResolvedPeriod> resolve(String message, LocalDate today) {
        if (message == null || today == null) {
            return Optional.empty();
        }
        String text = message.toLowerCase();

        Matcher isoRange = ISO_RANGE.matcher(text);
        if (isoRange.find()) {
            try {
                LocalDate from = LocalDate.parse(isoRange.group(1));
                LocalDate to = LocalDate.parse(isoRange.group(2));
                if (!from.isAfter(to)) {
                    return Optional.of(new ResolvedPeriod(from, to, "custom range"));
                }
            } catch (Exception ignored) {
                // fall through to other strategies
            }
        }

        Matcher monthDayRange = MONTH_DAY_RANGE.matcher(text);
        if (monthDayRange.find()) {
            Month fromMonth = MONTHS.get(monthDayRange.group(1).toLowerCase());
            String toMonthName = monthDayRange.group(3);
            Month toMonth = toMonthName == null || toMonthName.isBlank()
                    ? fromMonth : MONTHS.get(toMonthName.toLowerCase());
            if (fromMonth != null && toMonth != null) {
                try {
                    int year = monthDayRange.group(5) != null
                            ? Integer.parseInt(monthDayRange.group(5)) : today.getYear();
                    int fromDay = Integer.parseInt(monthDayRange.group(2));
                    int toDay = Integer.parseInt(monthDayRange.group(4));
                    LocalDate from = LocalDate.of(year, fromMonth, Math.min(fromDay, fromMonth.length(false)));
                    LocalDate to = LocalDate.of(year, toMonth, Math.min(toDay, toMonth.length(false)));
                    if (!from.isAfter(to)) {
                        return Optional.of(new ResolvedPeriod(from, to, "custom range"));
                    }
                } catch (Exception ignored) {
                    // fall through
                }
            }
        }

        if (text.contains("today")) {
            return Optional.of(new ResolvedPeriod(today, today, "today"));
        }
        if (text.contains("yesterday")) {
            LocalDate yesterday = today.minusDays(1);
            return Optional.of(new ResolvedPeriod(yesterday, yesterday, "yesterday"));
        }
        if (text.contains("this week")) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
            return Optional.of(new ResolvedPeriod(monday, sunday, "this week"));
        }
        if (text.contains("last week")) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
            return Optional.of(new ResolvedPeriod(monday, monday.plusDays(6), "last week"));
        }
        if (text.contains("this month")) {
            YearMonth ym = YearMonth.from(today);
            return Optional.of(new ResolvedPeriod(ym.atDay(1), ym.atEndOfMonth(), "this month"));
        }
        if (text.contains("last month")) {
            YearMonth ym = YearMonth.from(today).minusMonths(1);
            return Optional.of(new ResolvedPeriod(ym.atDay(1), ym.atEndOfMonth(), "last month"));
        }
        if (text.contains("this quarter")) {
            return Optional.of(quarterOf(today, "this quarter"));
        }
        if (text.contains("last quarter")) {
            LocalDate ref = today.minusMonths(3);
            return Optional.of(quarterOf(ref, "last quarter"));
        }
        if (text.contains("this year")) {
            return Optional.of(new ResolvedPeriod(
                    LocalDate.of(today.getYear(), 1, 1), LocalDate.of(today.getYear(), 12, 31), "this year"));
        }
        if (text.contains("last year")) {
            int year = today.getYear() - 1;
            return Optional.of(new ResolvedPeriod(
                    LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), "last year"));
        }

        Matcher monthYear = MONTH_YEAR.matcher(text);
        if (monthYear.find()) {
            Month month = MONTHS.get(monthYear.group(1).toLowerCase());
            if (month != null) {
                YearMonth ym = YearMonth.of(Integer.parseInt(monthYear.group(2)), month);
                return Optional.of(new ResolvedPeriod(ym.atDay(1), ym.atEndOfMonth(),
                        month.name().charAt(0) + month.name().substring(1).toLowerCase()
                                + " " + monthYear.group(2)));
            }
        }

        // Bare month name ("September") means that month in the current year.
        // Checked after relative expressions so "this month" wins.
        Matcher monthOnly = MONTH_ONLY.matcher(text);
        if (monthOnly.find() && !text.contains("this month") && !text.contains("last month")) {
            Month month = MONTHS.get(monthOnly.group(1).toLowerCase());
            if (month != null) {
                YearMonth ym = YearMonth.of(today.getYear(), month);
                return Optional.of(new ResolvedPeriod(ym.atDay(1), ym.atEndOfMonth(),
                        month.name().charAt(0) + month.name().substring(1).toLowerCase()
                                + " " + today.getYear()));
            }
        }

        return Optional.empty();
    }

    /**
     * Resolves a point-in-time date for balance-sheet style questions
     * ("as of September 30"). Falls back to a full period resolution and
     * uses its end date.
     */
    public Optional<LocalDate> resolveAsOf(String message, LocalDate today) {
        if (message == null || today == null) {
            return Optional.empty();
        }
        String text = message.toLowerCase();
        Matcher asOfIso = AS_OF_ISO.matcher(text);
        if (asOfIso.find()) {
            try {
                return Optional.of(LocalDate.parse(asOfIso.group(1)));
            } catch (Exception ignored) {
                // fall through
            }
        }
        Matcher asOfMonthDay = AS_OF_MONTH_DAY.matcher(text);
        if (asOfMonthDay.find()) {
            Month month = MONTHS.get(asOfMonthDay.group(1).toLowerCase());
            if (month != null) {
                try {
                    int year = asOfMonthDay.group(3) != null
                            ? Integer.parseInt(asOfMonthDay.group(3)) : today.getYear();
                    int day = Math.min(Integer.parseInt(asOfMonthDay.group(2)), month.length(false));
                    return Optional.of(LocalDate.of(year, month, day));
                } catch (Exception ignored) {
                    // fall through
                }
            }
        }
        return resolve(message, today).map(ResolvedPeriod::toDate);
    }

    private static ResolvedPeriod quarterOf(LocalDate ref, String label) {
        int quarter = (ref.getMonthValue() - 1) / 3;
        LocalDate from = LocalDate.of(ref.getYear(), quarter * 3 + 1, 1);
        LocalDate to = from.plusMonths(3).minusDays(1);
        return new ResolvedPeriod(from, to, label);
    }
}
