package com.example.accounting.ai;

import com.example.accounting.dto.StructuredInvoiceExtraction;
import com.example.accounting.entity.DocumentType;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@Profile("test")
@Primary
public class FakeAiDocumentExtractor implements AiDocumentExtractor {

    @Override
    public ExtractionResult extract(String ocrText, DocumentType documentType) {
        String lower = ocrText != null ? ocrText.toLowerCase() : "";

        // Handle corrupt/invalid cases
        if (lower.contains("corrupt") || lower.contains("invalid")) {
            throw new RuntimeException("Simulated AI extraction failure for corrupt text");
        }
        if (lower.contains("unsupported")) {
            throw new RuntimeException("Unsupported document simulation");
        }

        // Deterministic extraction for test
        // Check for specific known invoice text
        if (ocrText != null && ocrText.contains("INV-001") && ocrText.contains("ABC Supplies")) {
            StructuredInvoiceExtraction data = new StructuredInvoiceExtraction(
                    "INVOICE",
                    "INV-001",
                    "2026-01-15",
                    "2026-02-15",
                    "INR",
                    "ABC Supplies",
                    "GSTIN123",
                    "abc@supplies.com",
                    "1234567890",
                    "Acme Corp",
                    "GSTIN999",
                    new BigDecimal("1000.00"),
                    new BigDecimal("180.00"),
                    new BigDecimal("0.00"),
                    new BigDecimal("1180.00"),
                    "Net 30",
                    "Test invoice",
                    List.of(
                            new StructuredInvoiceExtraction.StructuredLineItem(
                                    "Item A", new BigDecimal("2"), new BigDecimal("500.00"),
                                    new BigDecimal("10"), new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("1100.00")
                            )
                    )
            );
            String raw = "{\"invoiceNumber\":\"INV-001\",\"totalAmount\":1180}";
            return new ExtractionResult(data, raw, "fake-model-v1", "invoice-extraction-v1", null);
        }

        // Default extraction based on documentType
        String docType = documentType != null ? documentType.name() : "OTHER";
        String vendorName = null;
        String customerName = null;
        if (ocrText != null && ocrText.toLowerCase().contains("vendor")) {
            vendorName = "Test Vendor";
        }
        if (ocrText != null && ocrText.toLowerCase().contains("customer")) {
            customerName = "Test Customer";
        }

        // Simulate extraction with some values from OCR text if present
        String invoiceNumber = null;
        if (ocrText != null && ocrText.contains("INV-")) {
            // extract first INV- pattern
            int idx = ocrText.indexOf("INV-");
            int end = ocrText.indexOf(" ", idx);
            if (end == -1) end = Math.min(idx + 15, ocrText.length());
            invoiceNumber = ocrText.substring(idx, end).trim();
        }

        BigDecimal subtotal = new BigDecimal("100.00");
        BigDecimal tax = new BigDecimal("10.00");
        BigDecimal total = new BigDecimal("110.00");

        // Try to parse numbers from OCR text if contains "Total"
        if (ocrText != null && ocrText.contains("Total")) {
            // keep defaults
        }

        StructuredInvoiceExtraction data = new StructuredInvoiceExtraction(
                docType,
                invoiceNumber,
                "2026-01-15",
                null,
                "INR",
                vendorName,
                null, null, null,
                customerName,
                null,
                subtotal,
                tax,
                BigDecimal.ZERO,
                total,
                "Net 30",
                null,
                List.of(
                        new StructuredInvoiceExtraction.StructuredLineItem(
                                "Extracted Item", BigDecimal.ONE, new BigDecimal("100.00"),
                                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00")
                        )
                )
        );
        String raw = "{\"fake\":\"response for " + docType + "\"}";
        return new ExtractionResult(data, raw, "fake-model-v1", "invoice-extraction-v1", null);
    }
}
