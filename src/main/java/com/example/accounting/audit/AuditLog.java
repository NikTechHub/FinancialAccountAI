package com.example.accounting.audit;

import com.example.accounting.entity.Organization;
import com.example.accounting.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit record. There is no API to modify or delete rows.
 * {@code organization} is nullable only for pre-authentication security
 * events (e.g. failed login with an unknown email) that cannot be
 * attributed to any organization; every authenticated or system event
 * carries exactly one organization. {@code actorUser} is null for
 * system/worker events and unattributable failures.
 */
@Entity
@Table(name = "audit_logs")
@EntityListeners(AuditingEntityListener.class)
public class AuditLog {

    @Id
    @UuidGenerator
    @Column(name = "id", columnDefinition = "CHAR(36)", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private User actorUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 50)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 30)
    private AuditResourceType resourceType;

    @Column(name = "resource_id", columnDefinition = "CHAR(36)")
    private UUID resourceId;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "before_data", columnDefinition = "LONGTEXT")
    private String beforeData;

    @Column(name = "after_data", columnDefinition = "LONGTEXT")
    private String afterData;

    @Column(name = "metadata", columnDefinition = "LONGTEXT")
    private String metadata;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "success", nullable = false)
    private Boolean success;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {
    }

    public AuditLog(Organization organization, User actorUser, AuditAction action,
                    AuditResourceType resourceType, UUID resourceId, String description,
                    String beforeData, String afterData, String metadata,
                    String ipAddress, String userAgent, boolean success) {
        this.organization = organization;
        this.actorUser = actorUser;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.description = description;
        this.beforeData = beforeData;
        this.afterData = afterData;
        this.metadata = metadata;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.success = success;
    }

    public UUID getId() {
        return id;
    }

    public Organization getOrganization() {
        return organization;
    }

    public User getActorUser() {
        return actorUser;
    }

    public AuditAction getAction() {
        return action;
    }

    public AuditResourceType getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getDescription() {
        return description;
    }

    public String getBeforeData() {
        return beforeData;
    }

    public String getAfterData() {
        return afterData;
    }

    public String getMetadata() {
        return metadata;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Boolean getSuccess() {
        return success;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
