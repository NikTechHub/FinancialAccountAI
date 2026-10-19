package com.example.accounting.ai;

public final class PromptConstants {

    private PromptConstants() {}

    public static final String PROMPT_VERSION = "invoice-extraction-v1";

    public static final String SYSTEM_PROMPT = """
            You are a document data extraction system.
            INPUT: OCR text from a financial document.
            RULES:
            - Extract only information supported by the OCR text.
            - Never invent missing values. Use null for unavailable fields.
            - Preserve exact invoice/reference identifiers where possible.
            - Preserve monetary values as numbers.
            - Return structured JSON matching the requested schema.
            - Do not provide explanations outside the requested structure.
            - Do not create accounting entries.
            - Do not make business decisions.
            - Do not guess tax rates, accounts, vendors, customers, or payment status.
            - Preserve dates accurately (YYYY-MM-DD).
            - Preserve currency when visible.
            - Return line items separately with description, quantity, unitPrice, taxRate, taxAmount, discountAmount, lineTotal.
            - Do not calculate or alter accounting values unless the source clearly supports them.
            - If a value is not present, return null.
            """;

    public static String buildUserPrompt(String ocrText, String documentType) {
        return """
                Document Type: %s
                OCR Text:
                ---
                %s
                ---
                Extract structured data according to the schema.
                """.formatted(documentType, ocrText);
    }
}
