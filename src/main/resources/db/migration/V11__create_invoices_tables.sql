CREATE TABLE invoices (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    customer_id CHAR(36) NOT NULL,
    invoice_number VARCHAR(50) NOT NULL,
    invoice_date DATE NOT NULL,
    due_date DATE NULL,
    status VARCHAR(20) NOT NULL,
    subtotal DECIMAL(19,4) NOT NULL,
    tax_amount DECIMAL(19,4) NOT NULL,
    discount_amount DECIMAL(19,4) NOT NULL,
    total_amount DECIMAL(19,4) NOT NULL,
    amount_paid DECIMAL(19,4) NOT NULL,
    balance_due DECIMAL(19,4) NOT NULL,
    notes VARCHAR(1000) NULL,
    terms VARCHAR(1000) NULL,
    journal_entry_id CHAR(36) NULL,
    created_by CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_invoice_org_number UNIQUE (organization_id, invoice_number),
    CONSTRAINT uk_invoice_journal_entry UNIQUE (journal_entry_id),
    CONSTRAINT fk_invoice_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_invoice_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_invoice_journal_entry FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_invoice_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_invoices_organization_id ON invoices (organization_id);
CREATE INDEX idx_invoices_org_status ON invoices (organization_id, status);
CREATE INDEX idx_invoices_org_customer ON invoices (organization_id, customer_id);
CREATE INDEX idx_invoices_org_invoice_date ON invoices (organization_id, invoice_date);
CREATE INDEX idx_invoices_org_due_date ON invoices (organization_id, due_date);
CREATE INDEX idx_invoices_org_number ON invoices (organization_id, invoice_number);
CREATE INDEX idx_invoices_journal_entry ON invoices (journal_entry_id);

CREATE TABLE invoice_lines (
    id CHAR(36) NOT NULL PRIMARY KEY,
    invoice_id CHAR(36) NOT NULL,
    description VARCHAR(500) NULL,
    quantity DECIMAL(19,4) NOT NULL,
    unit_price DECIMAL(19,4) NOT NULL,
    tax_rate DECIMAL(7,4) NOT NULL,
    discount_amount DECIMAL(19,4) NOT NULL,
    revenue_account_id CHAR(36) NOT NULL,
    line_subtotal DECIMAL(19,4) NOT NULL,
    line_tax_amount DECIMAL(19,4) NOT NULL,
    line_total DECIMAL(19,4) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_invoice_line_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_invoice_line_revenue_account FOREIGN KEY (revenue_account_id) REFERENCES accounts (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_invoice_lines_invoice_id ON invoice_lines (invoice_id);
CREATE INDEX idx_invoice_lines_revenue_account ON invoice_lines (revenue_account_id);
