CREATE TABLE vendors (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NULL,
    phone VARCHAR(50) NULL,
    address VARCHAR(500) NULL,
    tax_identifier VARCHAR(100) NULL,
    payment_terms VARCHAR(100) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_vendor_org_name UNIQUE (organization_id, name),
    CONSTRAINT fk_vendor_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_vendors_organization_id ON vendors (organization_id);
CREATE INDEX idx_vendors_name ON vendors (name);
CREATE INDEX idx_vendors_email ON vendors (email);
