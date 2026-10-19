package com.example.accounting.assistant;

/**
 * Allowlisted read-only intent categories. The classifier maps a user
 * message to exactly one of these; anything outside the list can never
 * trigger a data operation.
 */
public enum AssistantIntent {
    REVENUE,
    EXPENSES,
    PROFIT,
    BALANCE_SHEET,
    TRIAL_BALANCE,
    GENERAL_LEDGER,
    INVOICES,
    VENDOR_EXPENSES,
    DASHBOARD,
    /** Understood as financial but needs a period/account clarification. */
    CLARIFICATION,
    /** Write actions, out-of-scope requests, or unrecognized input. */
    UNSUPPORTED
}
