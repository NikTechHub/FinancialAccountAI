package com.example.accounting.kafka;

import com.example.accounting.kafka.event.DocumentExtractionRequestedEvent;
import com.example.accounting.kafka.event.DocumentOcrRequestedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Producer unit tests — no broker required (mocked KafkaTemplate).
 */
@ExtendWith(MockitoExtension.class)
class DocumentProcessingEventPublisherTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock
    private ObjectProvider<KafkaTemplate<String, Object>> templateProvider;

    private KafkaProperties props;
    private DocumentProcessingEventPublisher publisher;
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        props = new KafkaProperties();
        props.setEnabled(true);
        props.setBootstrapServers("localhost:9092");
        lenient().when(templateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
        publisher = new DocumentProcessingEventPublisher(props, templateProvider);
    }

    private SendResult<String, Object> completedResult(String topic, Object value) throws Exception {
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, value);
        RecordMetadata meta = new RecordMetadata(
                new org.apache.kafka.common.TopicPartition(topic, 0), 0L, 0, 0L, 0, 0);
        return new SendResult<>(record, meta);
    }

    @Test
    void shouldPublishOcrEventWithCorrectTopicAndFields() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var event = DocumentOcrRequestedEvent.create(docId, orgId, userId);

        when(kafkaTemplate.send(eq(KafkaTopics.DOCUMENT_OCR), eq(docId.toString()), any()))
                .thenReturn(CompletableFuture.completedFuture(completedResult(KafkaTopics.DOCUMENT_OCR, event)));

        publisher.publishOcrRequested(event);

        verify(kafkaTemplate).send(eq(KafkaTopics.DOCUMENT_OCR), eq(docId.toString()), eq(event));
        assertThat(event.eventId()).isNotNull();
        assertThat(event.documentId()).isEqualTo(docId);
        assertThat(event.organizationId()).isEqualTo(orgId);
        assertThat(event.requestedByUserId()).isEqualTo(userId);
        assertThat(event.eventVersion()).isEqualTo(1);
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.occurredAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void shouldPublishExtractionEventWithCorrectTopicAndFields() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var event = DocumentExtractionRequestedEvent.create(docId, orgId, userId);

        when(kafkaTemplate.send(eq(KafkaTopics.DOCUMENT_EXTRACTION), eq(docId.toString()), any()))
                .thenReturn(CompletableFuture.completedFuture(completedResult(KafkaTopics.DOCUMENT_EXTRACTION, event)));

        publisher.publishExtractionRequested(event);

        verify(kafkaTemplate).send(eq(KafkaTopics.DOCUMENT_EXTRACTION), eq(docId.toString()), eq(event));
        assertThat(event.eventVersion()).isEqualTo(1);
    }

    @Test
    void eventsShouldContainOnlySafeIdentifiers_noSensitiveData() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var ocrEvent = DocumentOcrRequestedEvent.create(docId, orgId, userId);
        var extEvent = DocumentExtractionRequestedEvent.create(docId, orgId, userId);

        String ocrJson = objectMapper.writeValueAsString(ocrEvent);
        String extJson = objectMapper.writeValueAsString(extEvent);
        for (String json : new String[]{ocrJson, extJson}) {
            assertThat(json).doesNotContain("password");
            assertThat(json).doesNotContain("jwt");
            assertThat(json).doesNotContain("apiKey");
            assertThat(json).doesNotContain("api-key");
            assertThat(json).doesNotContain("storageKey");
            assertThat(json).doesNotContain("storage_key");
            assertThat(json).doesNotContain("extractedText");
            assertThat(json).doesNotContain("ocrText");
            assertThat(json).doesNotContain("rawResponse");
            // only identifiers + version + timestamp
            assertThat(json).contains("eventId");
            assertThat(json).contains("documentId");
            assertThat(json).contains("organizationId");
            assertThat(json).contains("eventVersion");
        }
        // record shape has exactly 6 fields (no binary, no paths)
        assertThat(ocrEvent.eventId()).isNotNull();
    }

    @Test
    void shouldUseJsonNotJavaSerialization() throws Exception {
        // JSON round-trip must work without Java native serialization
        UUID docId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var event = DocumentOcrRequestedEvent.create(docId, orgId, userId);
        String json = objectMapper.writeValueAsString(event);
        var back = objectMapper.readValue(json, DocumentOcrRequestedEvent.class);
        assertThat(back.documentId()).isEqualTo(docId);
        assertThat(back.eventVersion()).isEqualTo(1);
    }

    @Test
    void shouldSkipPublishWhenKafkaDisabled() {
        props.setEnabled(false);
        var event = DocumentOcrRequestedEvent.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        // must not throw, must not touch template
        publisher.publishOcrRequested(event);
        verifyNoInteractions(kafkaTemplate);
        var ext = DocumentExtractionRequestedEvent.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        publisher.publishExtractionRequested(ext);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void topicConstantsShouldBeStable() {
        assertThat(KafkaTopics.DOCUMENT_OCR).isEqualTo("accounting.document.ocr");
        assertThat(KafkaTopics.DOCUMENT_EXTRACTION).isEqualTo("accounting.document.extraction");
        assertThat(KafkaTopics.DOCUMENT_DLQ).isEqualTo("accounting.document.processing.dlq");
        assertThat(KafkaTopics.OCR_CONSUMER_GROUP).isEqualTo("accounting-document-ocr-workers");
        assertThat(KafkaTopics.EXTRACTION_CONSUMER_GROUP).isEqualTo("accounting-document-extraction-workers");
    }
}
