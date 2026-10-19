CREATE TABLE accounts (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    code VARCHAR(20) NOT NULL,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(20) NOT NULL,
    parent_account_id CHAR(36) NULL,
    description VARCHAR(500) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    system_account BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_account_org_code UNIQUE (organization_id, code),
    CONSTRAINT fk_account_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_account_parent FOREIGN KEY (parent_account_id) REFERENCES accounts (id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_accounts_organization_id ON accounts (organization_id);
CREATE INDEX idx_accounts_org_code ON accounts (organization_id, code);
CREATE INDEX idx_accounts_org_type ON accounts (organization_id, type);
CREATE INDEX idx_accounts_org_parent ON accounts (organization_id, parent_account_id);
CREATE INDEX idx_accounts_name ON accounts (name);
