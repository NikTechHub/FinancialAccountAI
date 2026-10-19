CREATE TABLE documents (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    invoice_id CHAR(36) NULL,
    expense_id CHAR(36) NULL,
    original_file_name VARCHAR(255) NOT NULL,
    stored_file_name VARCHAR(255) NOT NULL,
    storage_key VARCHAR(1000) NOT NULL,
    content_type VARCHAR(150) NOT NULL,
    file_size BIGINT NOT NULL,
    document_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    uploaded_by CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_document_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_document_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id) ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT fk_document_expense FOREIGN KEY (expense_id) REFERENCES expenses (id) ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT fk_document_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_documents_organization_id ON documents (organization_id);
CREATE INDEX idx_documents_org_status ON documents (organization_id, status);
CREATE INDEX idx_documents_org_type ON documents (organization_id, document_type);
CREATE INDEX idx_documents_org_invoice ON documents (organization_id, invoice_id);
CREATE INDEX idx_documents_org_expense ON documents (organization_id, expense_id);
CREATE INDEX idx_documents_org_filename ON documents (organization_id, original_file_name);
CREATE INDEX idx_documents_checksum ON documents (checksum);
CREATE INDEX idx_documents_invoice_id ON documents (invoice_id);
CREATE INDEX idx_documents_expense_id ON documents (expense_id);
