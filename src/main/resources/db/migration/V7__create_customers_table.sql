CREATE TABLE customers (
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
    CONSTRAINT uk_customer_org_name UNIQUE (organization_id, name),
    CONSTRAINT fk_customer_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_customers_organization_id ON customers (organization_id);
CREATE INDEX idx_customers_org_name ON customers (organization_id, name);
CREATE INDEX idx_customers_org_email ON customers (organization_id, email);
