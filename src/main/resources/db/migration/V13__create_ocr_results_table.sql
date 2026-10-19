CREATE TABLE ocr_results (
    id CHAR(36) NOT NULL PRIMARY KEY,
    document_id CHAR(36) NOT NULL,
    status VARCHAR(30) NOT NULL,
    extracted_text LONGTEXT NULL,
    page_count INT NULL,
    processing_started_at DATETIME(6) NULL,
    processing_completed_at DATETIME(6) NULL,
    error_message VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_ocr_document UNIQUE (document_id),
    CONSTRAINT fk_ocr_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_ocr_status ON ocr_results (status);
CREATE INDEX idx_ocr_document_id ON ocr_results (document_id);
