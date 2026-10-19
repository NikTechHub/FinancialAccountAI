package com.example.accounting.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invoice_lines")
@EntityListeners(AuditingEntityListener.class)
public class InvoiceLine {

    @Id
    @UuidGenerator
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", columnDefinition = "CHAR(36)", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "quantity", precision = 19, scale = 4, nullable = false)
    private BigDecimal quantity;

    @Column(name = "unit_price", precision = 19, scale = 4, nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "tax_rate", precision = 7, scale = 4, nullable = false)
    private BigDecimal taxRate;

    @Column(name = "discount_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal discountAmount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "revenue_account_id", nullable = false)
    private Account revenueAccount;

    @Column(name = "line_subtotal", precision = 19, scale = 4, nullable = false)
    private BigDecimal lineSubtotal;

    @Column(name = "line_tax_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal lineTaxAmount;

    @Column(name = "line_total", precision = 19, scale = 4, nullable = false)
    private BigDecimal lineTotal;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InvoiceLine() {}

    public InvoiceLine(Invoice invoice, String description, BigDecimal quantity, BigDecimal unitPrice,
                       BigDecimal taxRate, BigDecimal discountAmount, Account revenueAccount,
                       BigDecimal lineSubtotal, BigDecimal lineTaxAmount, BigDecimal lineTotal) {
        this.invoice = invoice;
        this.description = description;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.taxRate = taxRate;
        this.discountAmount = discountAmount;
        this.revenueAccount = revenueAccount;
        this.lineSubtotal = lineSubtotal;
        this.lineTaxAmount = lineTaxAmount;
        this.lineTotal = lineTotal;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public void setInvoice(Invoice invoice) {
        this.invoice = invoice;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public void setTaxRate(BigDecimal taxRate) {
        this.taxRate = taxRate;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public Account getRevenueAccount() {
        return revenueAccount;
    }

    public void setRevenueAccount(Account revenueAccount) {
        this.revenueAccount = revenueAccount;
    }

    public BigDecimal getLineSubtotal() {
        return lineSubtotal;
    }

    public void setLineSubtotal(BigDecimal lineSubtotal) {
        this.lineSubtotal = lineSubtotal;
    }

    public BigDecimal getLineTaxAmount() {
        return lineTaxAmount;
    }

    public void setLineTaxAmount(BigDecimal lineTaxAmount) {
        this.lineTaxAmount = lineTaxAmount;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public void setLineTotal(BigDecimal lineTotal) {
        this.lineTotal = lineTotal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
