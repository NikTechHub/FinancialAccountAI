package com.example.accounting.kafka;

import com.example.accounting.kafka.event.DocumentExtractionRequestedEvent;
import com.example.accounting.kafka.event.DocumentOcrRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class DocumentProcessingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingEventPublisher.class);

    private final KafkaProperties kafkaProperties;
    private final ObjectProvider<KafkaTemplate<String, Object>> kafkaTemplateProvider;

    public DocumentProcessingEventPublisher(KafkaProperties kafkaProperties,
                                            ObjectProvider<KafkaTemplate<String, Object>> kafkaTemplateProvider) {
        this.kafkaProperties = kafkaProperties;
        this.kafkaTemplateProvider = kafkaTemplateProvider;
    }

    public void publishOcrRequested(DocumentOcrRequestedEvent event) {
        if (!kafkaProperties.isEnabled()) {
            log.info("Kafka disabled, skipping publish OCR event documentId={} eventId={}", sanitize(event.documentId()), sanitize(event.eventId()));
            return;
        }
        KafkaTemplate<String, Object> template = kafkaTemplateProvider.getIfAvailable();
        if (template == null) {
            log.warn("KafkaTemplate not available, cannot publish OCR event documentId={}", sanitize(event.documentId()));
            throw new IllegalStateException("Kafka not configured");
        }
        String topic = kafkaProperties.getTopics().getDocumentOcr();
        try {
            // Use documentId as key for ordering/partitioning
            var future = template.send(topic, event.documentId().toString(), event);
            // Wait synchronously to make transaction boundary safe (avoid stuck PENDING if publish fails)
            var result = future.get(10, TimeUnit.SECONDS);
            log.info("Published OCR event eventId={} documentId={} topic={} partition={} offset={}",
                    sanitize(event.eventId()), sanitize(event.documentId()), topic,
                    result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
        } catch (Exception e) {
            log.error("Failed to publish OCR event documentId={} eventId={}", sanitize(event.documentId()), sanitize(event.eventId()), e);
            throw new RuntimeException("Failed to publish OCR event", e);
        }
    }

    public void publishExtractionRequested(DocumentExtractionRequestedEvent event) {
        if (!kafkaProperties.isEnabled()) {
            log.info("Kafka disabled, skipping publish extraction event documentId={} eventId={}", sanitize(event.documentId()), sanitize(event.eventId()));
            return;
        }
        KafkaTemplate<String, Object> template = kafkaTemplateProvider.getIfAvailable();
        if (template == null) {
            log.warn("KafkaTemplate not available, cannot publish extraction event documentId={}", sanitize(event.documentId()));
            throw new IllegalStateException("Kafka not configured");
        }
        String topic = kafkaProperties.getTopics().getDocumentExtraction();
        try {
            var future = template.send(topic, event.documentId().toString(), event);
            var result = future.get(10, TimeUnit.SECONDS);
            log.info("Published extraction event eventId={} documentId={} topic={} partition={} offset={}",
                    sanitize(event.eventId()), sanitize(event.documentId()), topic,
                    result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
        } catch (Exception e) {
            log.error("Failed to publish extraction event documentId={} eventId={}", sanitize(event.documentId()), sanitize(event.eventId()), e);
            throw new RuntimeException("Failed to publish extraction event", e);
        }
    }

    private String sanitize(UUID id) {
        return id != null ? id.toString() : "null";
    }
}
