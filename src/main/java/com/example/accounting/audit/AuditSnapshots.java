package com.example.accounting.audit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sanitized before/after snapshot builders. Only safe business fields are
 * included — never passwords, hashes, tokens, secrets, file bytes, OCR
 * text, or raw model responses. Callers pass explicit values (never whole
 * entities) so nothing sensitive can slip in through lazy associations or
 * future field additions.
 */
public final class AuditSnapshots {

    private AuditSnapshots() {
    }

    public static Map<String, Object> user(String firstName, String lastName, String email, Boolean active) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("firstName", firstName);
        map.put("lastName", lastName);
        map.put("email", email);
        map.put("active", active);
        return map;
    }

    public static Map<String, Object> membership(UUID userId, UUID organizationId, String role) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", stringOf(userId));
        map.put("organizationId", stringOf(organizationId));
        map.put("role", role);
        return map;
    }

    public static Map<String, Object> organization(String name, String businessType,
                                                   String country, String currency, String timezone) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("businessType", businessType);
        map.put("country", country);
        map.put("currency", currency);
        map.put("timezone", timezone);
        return map;
    }

    public static Map<String, Object> vendor(String name, String email, String phone, String address,
                                             String taxIdentifier, String paymentTerms, Boolean active) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("email", email);
        map.put("phone", phone);
        map.put("address", address);
        map.put("taxIdentifier", taxIdentifier);
        map.put("paymentTerms", paymentTerms);
        map.put("active", active);
        return map;
    }

    public static Map<String, Object> customer(String name, String email, String phone, String address,
                                               String taxIdentifier, String paymentTerms, Boolean active) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("email", email);
        map.put("phone", phone);
        map.put("address", address);
        map.put("taxIdentifier", taxIdentifier);
        map.put("paymentTerms", paymentTerms);
        map.put("active", active);
        return map;
    }

    public static Map<String, Object> account(String code, String name, String type, UUID parentAccountId,
                                              String description, Boolean active) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("name", name);
        map.put("type", type);
        map.put("parentAccountId", stringOf(parentAccountId));
        map.put("description", description);
        map.put("active", active);
        return map;
    }

    public static Map<String, Object> journal(String entryNumber, LocalDate entryDate, String status,
                                              String description, String reference,
                                              BigDecimal totalDebits, BigDecimal totalCredits, int lineCount) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("entryNumber", entryNumber);
        map.put("entryDate", stringOf(entryDate));
        map.put("status", status);
        map.put("description", description);
        map.put("reference", reference);
        map.put("totalDebits", totalDebits);
        map.put("totalCredits", totalCredits);
        map.put("lineCount", lineCount);
        return map;
    }

    public static Map<String, Object> expense(UUID vendorId, UUID expenseAccountId, UUID paymentAccountId,
                                              BigDecimal amount, LocalDate expenseDate, String status,
                                              UUID journalEntryId, String reference) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("vendorId", stringOf(vendorId));
        map.put("expenseAccountId", stringOf(expenseAccountId));
        map.put("paymentAccountId", stringOf(paymentAccountId));
        map.put("amount", amount);
        map.put("expenseDate", stringOf(expenseDate));
        map.put("status", status);
        map.put("journalEntryId", stringOf(journalEntryId));
        map.put("reference", reference);
        return map;
    }

    public static Map<String, Object> invoice(String invoiceNumber, LocalDate invoiceDate, String status,
                                              BigDecimal subtotal, BigDecimal taxAmount, BigDecimal discountAmount,
                                              BigDecimal totalAmount, BigDecimal amountPaid, BigDecimal balanceDue,
                                              UUID customerId, String customerName, UUID journalEntryId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("invoiceNumber", invoiceNumber);
        map.put("invoiceDate", stringOf(invoiceDate));
        map.put("status", status);
        map.put("subtotal", subtotal);
        map.put("taxAmount", taxAmount);
        map.put("discountAmount", discountAmount);
        map.put("totalAmount", totalAmount);
        map.put("amountPaid", amountPaid);
        map.put("balanceDue", balanceDue);
        map.put("customerId", stringOf(customerId));
        map.put("customerName", customerName);
        map.put("journalEntryId", stringOf(journalEntryId));
        return map;
    }

    public static Map<String, Object> document(String originalFileName, String contentType, Long fileSize,
                                               String documentType, String status, String checksum) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("originalFileName", originalFileName);
        map.put("contentType", contentType);
        map.put("fileSize", fileSize);
        map.put("documentType", documentType);
        map.put("status", status);
        map.put("checksum", checksum);
        return map;
    }

    public static Map<String, Object> review(String status, UUID documentId, UUID extractionId,
                                             String reviewComment) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        map.put("documentId", stringOf(documentId));
        map.put("extractionId", stringOf(extractionId));
        map.put("reviewComment", reviewComment);
        return map;
    }

    public static Map<String, Object> ocr(String status, UUID documentId, Integer pageCount) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        map.put("documentId", stringOf(documentId));
        map.put("pageCount", pageCount);
        return map;
    }

    public static Map<String, Object> extraction(String status, UUID documentId, String extractionType,
                                                String model, String promptVersion) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        map.put("documentId", stringOf(documentId));
        map.put("extractionType", extractionType);
        map.put("model", model);
        map.put("promptVersion", promptVersion);
        return map;
    }

    /**
     * Safe metadata envelope. The endpoint is captured automatically when
     * an HTTP request is in scope; callers add only non-secret context.
     */
    public static Map<String, Object> metadata(String source, Map<String, Object> extra) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("source", source);
        String endpoint = com.example.accounting.audit.RequestAuditContext.endpoint();
        if (endpoint != null) {
            map.put("endpoint", endpoint);
        }
        if (extra != null) {
            map.putAll(extra);
        }
        return map;
    }

    public static Map<String, Object> of(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private static String stringOf(Object value) {
        return value == null ? null : value.toString();
    }
}
