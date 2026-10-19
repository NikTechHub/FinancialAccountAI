package com.example.accounting.kafka;

import com.example.accounting.kafka.event.DocumentExtractionRequestedEvent;
import com.example.accounting.service.DocumentExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class DocumentExtractionKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractionKafkaConsumer.class);

    private final DocumentExtractionService extractionService;

    public DocumentExtractionKafkaConsumer(DocumentExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.document-extraction:" + KafkaTopics.DOCUMENT_EXTRACTION + "}",
            groupId = "${app.kafka.consumer.extraction-group-id:" + KafkaTopics.EXTRACTION_CONSUMER_GROUP + "}",
            containerFactory = "extractionKafkaListenerContainerFactory"
    )
    public void onExtractionRequested(DocumentExtractionRequestedEvent event) {
        log.info("Received extraction event eventId={} documentId={} organizationId={}", event.eventId(), event.documentId(), event.organizationId());
        try {
            extractionService.processExtraction(event);
        } catch (IllegalArgumentException e) {
            log.warn("Permanent extraction failure eventId={} documentId={} reason={}", event.eventId(), event.documentId(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Transient extraction failure eventId={} documentId={}", event.eventId(), event.documentId(), e);
            throw new RuntimeException(e);
        }
    }
}
