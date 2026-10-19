package com.example.accounting.kafka;

import com.example.accounting.kafka.event.DocumentExtractionRequestedEvent;
import com.example.accounting.kafka.event.DocumentOcrRequestedEvent;
import com.example.accounting.service.DocumentExtractionService;
import com.example.accounting.service.OcrService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Consumer delegation unit tests — no broker required.
 */
@ExtendWith(MockitoExtension.class)
class KafkaConsumerUnitTest {

    @Mock
    private OcrService ocrService;
    @Mock
    private DocumentExtractionService extractionService;

    @Test
    void ocrConsumerShouldDelegateToOcrService() {
        DocumentOcrKafkaConsumer consumer = new DocumentOcrKafkaConsumer(ocrService);
        var event = DocumentOcrRequestedEvent.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        consumer.onOcrRequested(event);
        verify(ocrService).processOcr(event);
    }

    @Test
    void ocrConsumerShouldPropagatePermanentFailures() {
        DocumentOcrKafkaConsumer consumer = new DocumentOcrKafkaConsumer(ocrService);
        var event = DocumentOcrRequestedEvent.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        doThrow(new IllegalArgumentException("bad event")).when(ocrService).processOcr(event);
        assertThatThrownBy(() -> consumer.onOcrRequested(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ocrConsumerShouldWrapTransientFailures() {
        DocumentOcrKafkaConsumer consumer = new DocumentOcrKafkaConsumer(ocrService);
        var event = DocumentOcrRequestedEvent.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        doThrow(new RuntimeException("db down")).when(ocrService).processOcr(event);
        assertThatThrownBy(() -> consumer.onOcrRequested(event)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void extractionConsumerShouldDelegateToExtractionService() {
        DocumentExtractionKafkaConsumer consumer = new DocumentExtractionKafkaConsumer(extractionService);
        var event = DocumentExtractionRequestedEvent.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        consumer.onExtractionRequested(event);
        verify(extractionService).processExtraction(event);
    }

    @Test
    void extractionConsumerShouldPropagatePermanentFailures() {
        DocumentExtractionKafkaConsumer consumer = new DocumentExtractionKafkaConsumer(extractionService);
        var event = DocumentExtractionRequestedEvent.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        doThrow(new IllegalArgumentException("ocr incomplete")).when(extractionService).processExtraction(event);
        assertThatThrownBy(() -> consumer.onExtractionRequested(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractionConsumerShouldWrapTransientFailures() {
        DocumentExtractionKafkaConsumer consumer = new DocumentExtractionKafkaConsumer(extractionService);
        var event = DocumentExtractionRequestedEvent.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        doThrow(new RuntimeException("llm timeout")).when(extractionService).processExtraction(event);
        assertThatThrownBy(() -> consumer.onExtractionRequested(event)).isInstanceOf(RuntimeException.class);
    }
}
