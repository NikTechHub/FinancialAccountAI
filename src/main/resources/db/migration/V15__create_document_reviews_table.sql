CREATE TABLE document_reviews (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    document_id CHAR(36) NOT NULL,
    extraction_id CHAR(36) NOT NULL,
    status VARCHAR(30) NOT NULL,
    reviewed_data LONGTEXT NULL,
    reviewed_by CHAR(36) NULL,
    reviewed_at DATETIME(6) NULL,
    review_comment VARCHAR(2000) NULL,
    version BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_review_extraction UNIQUE (extraction_id),
    CONSTRAINT fk_review_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_review_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_review_extraction FOREIGN KEY (extraction_id) REFERENCES document_extractions (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_review_user FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_reviews_organization_id ON document_reviews (organization_id);
CREATE INDEX idx_reviews_document_id ON document_reviews (document_id);
CREATE INDEX idx_reviews_extraction_id ON document_reviews (extraction_id);
CREATE INDEX idx_reviews_status ON document_reviews (status);
