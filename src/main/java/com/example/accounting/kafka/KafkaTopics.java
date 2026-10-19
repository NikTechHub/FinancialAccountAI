package com.example.accounting.kafka;

public final class KafkaTopics {

    private KafkaTopics() {}

    public static final String DOCUMENT_OCR = "accounting.document.ocr";
    public static final String DOCUMENT_EXTRACTION = "accounting.document.extraction";
    public static final String DOCUMENT_DLQ = "accounting.document.processing.dlq";

    public static final String OCR_CONSUMER_GROUP = "accounting-document-ocr-workers";
    public static final String EXTRACTION_CONSUMER_GROUP = "accounting-document-extraction-workers";
}
