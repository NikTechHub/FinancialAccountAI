CREATE TABLE expenses (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    vendor_id CHAR(36) NULL,
    expense_account_id CHAR(36) NOT NULL,
    payment_account_id CHAR(36) NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    expense_date DATE NOT NULL,
    description VARCHAR(500) NULL,
    reference VARCHAR(100) NULL,
    status VARCHAR(20) NOT NULL,
    journal_entry_id CHAR(36) NULL,
    created_by CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_expense_journal_entry UNIQUE (journal_entry_id),
    CONSTRAINT fk_expense_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_expense_vendor FOREIGN KEY (vendor_id) REFERENCES vendors (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_expense_expense_account FOREIGN KEY (expense_account_id) REFERENCES accounts (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_expense_payment_account FOREIGN KEY (payment_account_id) REFERENCES accounts (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_expense_journal_entry FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_expense_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_expenses_organization_id ON expenses (organization_id);
CREATE INDEX idx_expenses_org_status ON expenses (organization_id, status);
CREATE INDEX idx_expenses_org_date ON expenses (organization_id, expense_date);
CREATE INDEX idx_expenses_org_vendor ON expenses (organization_id, vendor_id);
CREATE INDEX idx_expenses_org_expense_account ON expenses (organization_id, expense_account_id);
CREATE INDEX idx_expenses_org_reference ON expenses (organization_id, reference);
CREATE INDEX idx_expenses_journal_entry ON expenses (journal_entry_id);
CREATE INDEX idx_expenses_created_by ON expenses (created_by);
