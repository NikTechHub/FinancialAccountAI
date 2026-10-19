CREATE TABLE document_extractions (
    id CHAR(36) NOT NULL PRIMARY KEY,
    document_id CHAR(36) NOT NULL,
    organization_id CHAR(36) NOT NULL,
    extraction_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    model VARCHAR(100) NULL,
    prompt_version VARCHAR(50) NULL,
    raw_response LONGTEXT NULL,
    structured_data LONGTEXT NULL,
    confidence DECIMAL(5,4) NULL,
    error_message VARCHAR(2000) NULL,
    processing_started_at DATETIME(6) NULL,
    processing_completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_extraction_document UNIQUE (document_id),
    CONSTRAINT fk_extraction_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_extraction_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_extractions_organization_id ON document_extractions (organization_id);
CREATE INDEX idx_extractions_status ON document_extractions (status);
CREATE INDEX idx_extractions_type ON document_extractions (extraction_type);
CREATE INDEX idx_extractions_document_id ON document_extractions (document_id);
