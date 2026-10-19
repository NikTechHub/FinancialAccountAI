CREATE TABLE journal_entries (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    entry_number VARCHAR(50) NOT NULL,
    entry_date DATE NOT NULL,
    description VARCHAR(500) NULL,
    reference VARCHAR(100) NULL,
    status VARCHAR(20) NOT NULL,
    created_by CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_journal_org_entry_number UNIQUE (organization_id, entry_number),
    CONSTRAINT fk_journal_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_journal_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_journals_organization_id ON journal_entries (organization_id);
CREATE INDEX idx_journals_status ON journal_entries (organization_id, status);
CREATE INDEX idx_journals_entry_date ON journal_entries (organization_id, entry_date);
CREATE INDEX idx_journals_reference ON journal_entries (organization_id, reference);

CREATE TABLE journal_entry_lines (
    id CHAR(36) NOT NULL PRIMARY KEY,
    journal_entry_id CHAR(36) NOT NULL,
    account_id CHAR(36) NOT NULL,
    description VARCHAR(500) NULL,
    debit_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    credit_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_line_journal FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_line_account FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_lines_journal_id ON journal_entry_lines (journal_entry_id);
CREATE INDEX idx_lines_account_id ON journal_entry_lines (account_id);
