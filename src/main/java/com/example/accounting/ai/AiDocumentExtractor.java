package com.example.accounting.ai;

import com.example.accounting.dto.StructuredInvoiceExtraction;
import com.example.accounting.entity.DocumentType;

import java.math.BigDecimal;

public interface AiDocumentExtractor {

    ExtractionResult extract(String ocrText, DocumentType documentType);

    record ExtractionResult(
            StructuredInvoiceExtraction structuredData,
            String rawResponse,
            String model,
            String promptVersion,
            BigDecimal confidence
    ) {
        public ExtractionResult(StructuredInvoiceExtraction structuredData,
                                String rawResponse,
                                String model,
                                String promptVersion) {
            this(structuredData, rawResponse, model, promptVersion, null);
        }
    }
}
