package com.example.accounting.audit;

/**
 * Controlled audit resource vocabulary. Business terminology is used
 * instead of raw Java class names.
 */
public enum AuditResourceType {
    ORGANIZATION,
    USER,
    MEMBERSHIP,
    VENDOR,
    CUSTOMER,
    ACCOUNT,
    JOURNAL_ENTRY,
    EXPENSE,
    INVOICE,
    DOCUMENT,
    OCR_RESULT,
    DOCUMENT_EXTRACTION,
    DOCUMENT_REVIEW,
    REPORT,
    ASSISTANT
}
