CREATE TABLE audit_logs (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NULL,
    actor_user_id CHAR(36) NULL,
    action VARCHAR(50) NOT NULL,
    resource_type VARCHAR(30) NOT NULL,
    resource_id CHAR(36) NULL,
    description VARCHAR(1000) NULL,
    before_data LONGTEXT NULL,
    after_data LONGTEXT NULL,
    metadata LONGTEXT NULL,
    ip_address VARCHAR(45) NULL,
    user_agent VARCHAR(500) NULL,
    success BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_audit_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_audit_actor_user FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_audit_org_created ON audit_logs (organization_id, created_at);
CREATE INDEX idx_audit_org_resource ON audit_logs (organization_id, resource_type, resource_id);
CREATE INDEX idx_audit_org_actor_created ON audit_logs (organization_id, actor_user_id, created_at);
CREATE INDEX idx_audit_org_action_created ON audit_logs (organization_id, action, created_at);
