package com.example.accounting.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.kafka")
public class KafkaProperties {

    private boolean enabled = true;
    private String bootstrapServers = "localhost:9092";
    private Topics topics = new Topics();
    private Consumer consumer = new Consumer();
    private Producer producer = new Producer();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }

    public Topics getTopics() { return topics; }
    public void setTopics(Topics topics) { this.topics = topics; }

    public Consumer getConsumer() { return consumer; }
    public void setConsumer(Consumer consumer) { this.consumer = consumer; }

    public Producer getProducer() { return producer; }
    public void setProducer(Producer producer) { this.producer = producer; }

    public static class Topics {
        private String documentOcr = KafkaTopics.DOCUMENT_OCR;
        private String documentExtraction = KafkaTopics.DOCUMENT_EXTRACTION;
        private String documentDlq = KafkaTopics.DOCUMENT_DLQ;

        public String getDocumentOcr() { return documentOcr; }
        public void setDocumentOcr(String documentOcr) { this.documentOcr = documentOcr; }

        public String getDocumentExtraction() { return documentExtraction; }
        public void setDocumentExtraction(String documentExtraction) { this.documentExtraction = documentExtraction; }

        public String getDocumentDlq() { return documentDlq; }
        public void setDocumentDlq(String documentDlq) { this.documentDlq = documentDlq; }
    }

    public static class Consumer {
        private String ocrGroupId = KafkaTopics.OCR_CONSUMER_GROUP;
        private String extractionGroupId = KafkaTopics.EXTRACTION_CONSUMER_GROUP;
        private int maxRetries = 3;
        private long backoffMs = 1000L;

        public String getOcrGroupId() { return ocrGroupId; }
        public void setOcrGroupId(String ocrGroupId) { this.ocrGroupId = ocrGroupId; }

        public String getExtractionGroupId() { return extractionGroupId; }
        public void setExtractionGroupId(String extractionGroupId) { this.extractionGroupId = extractionGroupId; }

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

        public long getBackoffMs() { return backoffMs; }
        public void setBackoffMs(long backoffMs) { this.backoffMs = backoffMs; }
    }

    public static class Producer {
        private String acks = "all";
        private int retries = 3;

        public String getAcks() { return acks; }
        public void setAcks(String acks) { this.acks = acks; }

        public int getRetries() { return retries; }
        public void setRetries(int retries) { this.retries = retries; }
    }
}
