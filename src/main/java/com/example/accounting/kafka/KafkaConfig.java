package com.example.accounting.kafka;

import com.example.accounting.kafka.event.DocumentExtractionRequestedEvent;
import com.example.accounting.kafka.event.DocumentOcrRequestedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(KafkaProperties.class)
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    // Producer
    @Bean
    @ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
    public ProducerFactory<String, Object> producerFactory(KafkaProperties props, ObjectMapper objectMapper) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, props.getProducer().getAcks());
        config.put(ProducerConfig.RETRIES_CONFIG, props.getProducer().getRetries());
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // JsonSerializer will use ObjectMapper
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    @ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> pf) {
        return new KafkaTemplate<>(pf);
    }

    // Consumers - generic factory for Ocr
    @Bean
    @ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
    public ConsumerFactory<String, DocumentOcrRequestedEvent> ocrConsumerFactory(KafkaProperties props, ObjectMapper objectMapper) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, props.getConsumer().getOcrGroupId());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.accounting.kafka.event");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, DocumentOcrRequestedEvent.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), new JsonDeserializer<>(DocumentOcrRequestedEvent.class, objectMapper, false));
    }

    @Bean
    @ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
    public ConsumerFactory<String, DocumentExtractionRequestedEvent> extractionConsumerFactory(KafkaProperties props, ObjectMapper objectMapper) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, props.getConsumer().getExtractionGroupId());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.accounting.kafka.event");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, DocumentExtractionRequestedEvent.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), new JsonDeserializer<>(DocumentExtractionRequestedEvent.class, objectMapper, false));
    }

    @Bean
    @ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
    public ConcurrentKafkaListenerContainerFactory<String, DocumentOcrRequestedEvent> ocrKafkaListenerContainerFactory(
            ConsumerFactory<String, DocumentOcrRequestedEvent> cf,
            KafkaTemplate<String, Object> template,
            KafkaProperties props) {
        ConcurrentKafkaListenerContainerFactory<String, DocumentOcrRequestedEvent> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        // Error handling with DLQ after retries
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(props.getTopics().getDocumentDlq(), record.partition()));
        FixedBackOff backOff = new FixedBackOff(props.getConsumer().getBackoffMs(), props.getConsumer().getMaxRetries());
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        // Not retrying for certain exceptions (validation, not found)
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean
    @ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
    public ConcurrentKafkaListenerContainerFactory<String, DocumentExtractionRequestedEvent> extractionKafkaListenerContainerFactory(
            ConsumerFactory<String, DocumentExtractionRequestedEvent> cf,
            KafkaTemplate<String, Object> template,
            KafkaProperties props) {
        ConcurrentKafkaListenerContainerFactory<String, DocumentExtractionRequestedEvent> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(props.getTopics().getDocumentDlq(), record.partition()));
        FixedBackOff backOff = new FixedBackOff(props.getConsumer().getBackoffMs(), props.getConsumer().getMaxRetries());
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
