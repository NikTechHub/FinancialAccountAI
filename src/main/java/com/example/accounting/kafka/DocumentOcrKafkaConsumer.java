package com.example.accounting.kafka;

import com.example.accounting.kafka.event.DocumentOcrRequestedEvent;
import com.example.accounting.service.OcrService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class DocumentOcrKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentOcrKafkaConsumer.class);

    private final OcrService ocrService;

    public DocumentOcrKafkaConsumer(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.document-ocr:" + KafkaTopics.DOCUMENT_OCR + "}",
            groupId = "${app.kafka.consumer.ocr-group-id:" + KafkaTopics.OCR_CONSUMER_GROUP + "}",
            containerFactory = "ocrKafkaListenerContainerFactory"
    )
    public void onOcrRequested(DocumentOcrRequestedEvent event) {
        log.info("Received OCR event eventId={} documentId={} organizationId={}", event.eventId(), event.documentId(), event.organizationId());
        try {
            ocrService.processOcr(event);
        } catch (IllegalArgumentException e) {
            // Permanent failure, will be handled by error handler (DLQ) but log safe diagnostic
            log.warn("Permanent OCR failure eventId={} documentId={} reason={}", event.eventId(), event.documentId(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Transient OCR failure eventId={} documentId={}", event.eventId(), event.documentId(), e);
            throw new RuntimeException(e);
        }
    }
}
