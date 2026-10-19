package com.example.accounting.assistant;

import org.springframework.stereotype.Component;

/**
 * Deterministic rule-based intent classification. No LLM is involved, so
 * classification is stable, offline-testable, and can never invent an
 * operation outside the allowlist. Write/out-of-scope requests are
 * classified as UNSUPPORTED before anything else.
 */
@Component
public class IntentClassifier {

    public AssistantIntent classify(String message) {
        if (message == null || message.isBlank()) {
            return AssistantIntent.UNSUPPORTED;
        }
        String text = message.toLowerCase();

        if (isWriteOrOutOfScope(text)) {
            return AssistantIntent.UNSUPPORTED;
        }
        if (containsAny(text, "trial balance")) {
            return AssistantIntent.TRIAL_BALANCE;
        }
        if (containsAny(text, "general ledger", "ledger", "account activity", "transactions affected",
                "account 1", "account 2", "account 3", "account 4", "account 5", "account 6",
                "account 7", "account 8", "account 9", "cash account", "accounts receivable")) {
            return AssistantIntent.GENERAL_LEDGER;
        }
        if (containsAny(text, "balance sheet", "assets", "liabilit", "equity", "how much do we owe",
                "what do we own")) {
            return AssistantIntent.BALANCE_SHEET;
        }
        if (containsAny(text, "vendor") && containsAny(text, "spend", "spent", "expense", "paid", "payment",
                "most", "largest", "biggest", "top", "which", "how much")) {
            return AssistantIntent.VENDOR_EXPENSES;
        }
        if (containsAny(text, "invoice", "invoiced", "outstanding", "overdue", "receivable",
                "owe us", "owes us", "customers owe", "unpaid", "amount due", "balance due")) {
            return AssistantIntent.INVOICES;
        }
        if (containsAny(text, "net profit", "profit", "did we make money", "bottom line", "net income",
                "net loss", "profitable")) {
            return AssistantIntent.PROFIT;
        }
        // "Spend" questions that name the revenue word need care: check
        // expenses before revenue so "spend/revenue" mixes prefer expenses.
        if (containsAny(text, "expense", "expenses", "spend", "spent", "spending", "cost",
                "biggest expense", "largest expense", "expense categor")) {
            return AssistantIntent.EXPENSES;
        }
        if (containsAny(text, "revenue", "revenues", "sales", "income", "turnover", "how much did we make")) {
            return AssistantIntent.REVENUE;
        }
        if (containsAny(text, "summar", "overview", "performance", "how are we doing", "dashboard",
                "financial health", "snapshot")) {
            return AssistantIntent.DASHBOARD;
        }
        if (containsAny(text, "revenue", "expense", "profit", "invoice", "balance", "account",
                "ledger", "financial", "money", "spend", "sales")) {
            return AssistantIntent.CLARIFICATION;
        }
        return AssistantIntent.UNSUPPORTED;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Anything that would mutate state or leave the financial Q&amp;A scope.
     * Checked first so "delete this expense report" can never classify as a
     * report intent.
     */
    static boolean isWriteOrOutOfScope(String text) {
        return containsAny(text,
                "create ", "post a ", "post an ", "void ", "delete ", "remove ",
                "pay this", "pay the", "make a payment", "send money", "transfer ",
                "approve", "reject ", "send an email", "send email", "email ",
                "change our bank", "bank account", "update our bank",
                "create a journal", "create an invoice", "create an expense",
                "issue an invoice", "record an expense", "add a vendor", "add a customer",
                "change the amount", "update the account", "modify ", "reset ",
                "loan application", "apply for", "invest in", "buy stock", "buy shares",
                "tax filing", "file tax", "hire ", "fire ", "weather", "joke",
                "who are you", "your name");
    }
}
