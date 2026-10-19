package com.example.accounting.report;

import com.example.accounting.entity.AccountType;
import com.example.accounting.entity.ExpenseStatus;
import com.example.accounting.entity.InvoiceStatus;
import com.example.accounting.entity.JournalEntryStatus;
import com.example.accounting.report.dto.ExpenseAccountSummaryLine;
import com.example.accounting.report.dto.GeneralLedgerLine;
import com.example.accounting.report.dto.VendorExpenseSummaryLine;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Reporting queries. All aggregation happens in the database (no full-table
 * loads into Java memory). Every query filters by the caller's validated
 * organization IDs and never accepts an organization ID from the HTTP layer.
 * Only the statuses passed by {@code ReportService} are included.
 */
@Repository
@Transactional(readOnly = true)
public class ReportRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Per-account debit/credit totals from POSTED lines in an inclusive date range.
     * Only accounts with activity in the range are returned.
     */
    public List<AccountTotals> accountTotalsInRange(List<UUID> orgIds, LocalDate from, LocalDate to,
                                                    List<AccountType> types) {
        String jpql = "SELECT NEW com.example.accounting.report.AccountTotals("
                + "a.id, a.code, a.name, a.type, SUM(l.debitAmount), SUM(l.creditAmount)) "
                + "FROM JournalEntryLine l JOIN l.journalEntry j JOIN l.account a "
                + "WHERE j.organization.id IN :orgIds "
                + "AND j.status = :status "
                + "AND j.entryDate BETWEEN :from AND :to "
                + (types != null ? "AND a.type IN :types " : "")
                + "GROUP BY a.id, a.code, a.name, a.type "
                + "ORDER BY a.code ASC";
        TypedQuery<AccountTotals> query = entityManager.createQuery(jpql, AccountTotals.class);
        query.setParameter("orgIds", orgIds);
        query.setParameter("status", JournalEntryStatus.POSTED);
        query.setParameter("from", from);
        query.setParameter("to", to);
        if (types != null) {
            query.setParameter("types", types);
        }
        return query.getResultList();
    }

    /**
     * Per-account debit/credit totals from POSTED lines dated on or before asOf.
     */
    public List<AccountTotals> accountTotalsAsOf(List<UUID> orgIds, LocalDate asOf, List<AccountType> types) {
        String jpql = "SELECT NEW com.example.accounting.report.AccountTotals("
                + "a.id, a.code, a.name, a.type, SUM(l.debitAmount), SUM(l.creditAmount)) "
                + "FROM JournalEntryLine l JOIN l.journalEntry j JOIN l.account a "
                + "WHERE j.organization.id IN :orgIds "
                + "AND j.status = :status "
                + "AND j.entryDate <= :asOf "
                + (types != null ? "AND a.type IN :types " : "")
                + "GROUP BY a.id, a.code, a.name, a.type "
                + "ORDER BY a.code ASC";
        TypedQuery<AccountTotals> query = entityManager.createQuery(jpql, AccountTotals.class);
        query.setParameter("orgIds", orgIds);
        query.setParameter("status", JournalEntryStatus.POSTED);
        query.setParameter("asOf", asOf);
        if (types != null) {
            query.setParameter("types", types);
        }
        return query.getResultList();
    }

    /**
     * Journal-line details for one account, deterministically ordered by
     * entry date, entry number, then line ID.
     */
    public List<GeneralLedgerLine> ledgerLines(UUID accountId, List<UUID> orgIds,
                                               LocalDate from, LocalDate to, int offset, int limit) {
        String jpql = "SELECT NEW com.example.accounting.report.dto.GeneralLedgerLine("
                + "j.id, j.entryNumber, j.entryDate, j.description, j.reference, "
                + "a.id, a.code, a.name, l.description, l.debitAmount, l.creditAmount) "
                + "FROM JournalEntryLine l JOIN l.journalEntry j JOIN l.account a "
                + "WHERE a.id = :accountId "
                + "AND j.organization.id IN :orgIds "
                + "AND j.status = :status "
                + "AND j.entryDate BETWEEN :from AND :to "
                + "ORDER BY j.entryDate ASC, j.entryNumber ASC, l.id ASC";
        TypedQuery<GeneralLedgerLine> query = entityManager.createQuery(jpql, GeneralLedgerLine.class);
        query.setParameter("accountId", accountId);
        query.setParameter("orgIds", orgIds);
        query.setParameter("status", JournalEntryStatus.POSTED);
        query.setParameter("from", from);
        query.setParameter("to", to);
        query.setFirstResult(offset);
        query.setMaxResults(limit);
        return query.getResultList();
    }

    public long countLedgerLines(UUID accountId, List<UUID> orgIds, LocalDate from, LocalDate to) {
        String jpql = "SELECT COUNT(l) "
                + "FROM JournalEntryLine l JOIN l.journalEntry j JOIN l.account a "
                + "WHERE a.id = :accountId "
                + "AND j.organization.id IN :orgIds "
                + "AND j.status = :status "
                + "AND j.entryDate BETWEEN :from AND :to";
        TypedQuery<Long> query = entityManager.createQuery(jpql, Long.class);
        query.setParameter("accountId", accountId);
        query.setParameter("orgIds", orgIds);
        query.setParameter("status", JournalEntryStatus.POSTED);
        query.setParameter("from", from);
        query.setParameter("to", to);
        return query.getSingleResult();
    }

    /**
     * RECORDED expenses grouped by expense account.
     * Authoritative source: Expense records (not their journals) so vendor
     * attribution is available and amounts are never double-counted.
     */
    public List<ExpenseAccountSummaryLine> expensesByAccount(List<UUID> orgIds, LocalDate from, LocalDate to,
                                                            UUID vendorId) {
        String jpql = "SELECT NEW com.example.accounting.report.dto.ExpenseAccountSummaryLine("
                + "a.id, a.code, a.name, SUM(e.amount), COUNT(e)) "
                + "FROM Expense e JOIN e.expenseAccount a "
                + "WHERE e.organization.id IN :orgIds "
                + "AND e.status = :status "
                + "AND e.expenseDate BETWEEN :from AND :to "
                + (vendorId != null ? "AND e.vendor.id = :vendorId " : "")
                + "GROUP BY a.id, a.code, a.name "
                + "ORDER BY a.code ASC";
        TypedQuery<ExpenseAccountSummaryLine> query =
                entityManager.createQuery(jpql, ExpenseAccountSummaryLine.class);
        query.setParameter("orgIds", orgIds);
        query.setParameter("status", ExpenseStatus.RECORDED);
        query.setParameter("from", from);
        query.setParameter("to", to);
        if (vendorId != null) {
            query.setParameter("vendorId", vendorId);
        }
        return query.getResultList();
    }

    /**
     * RECORDED expenses with a vendor, grouped by vendor.
     */
    public List<VendorExpenseSummaryLine> expensesByVendor(List<UUID> orgIds, LocalDate from, LocalDate to,
                                                          UUID vendorId) {
        String jpql = "SELECT NEW com.example.accounting.report.dto.VendorExpenseSummaryLine("
                + "v.id, v.name, SUM(e.amount), COUNT(e)) "
                + "FROM Expense e JOIN e.vendor v "
                + "WHERE e.organization.id IN :orgIds "
                + "AND e.status = :status "
                + "AND e.expenseDate BETWEEN :from AND :to "
                + (vendorId != null ? "AND v.id = :vendorId " : "")
                + "GROUP BY v.id, v.name "
                + "ORDER BY v.name ASC";
        TypedQuery<VendorExpenseSummaryLine> query =
                entityManager.createQuery(jpql, VendorExpenseSummaryLine.class);
        query.setParameter("orgIds", orgIds);
        query.setParameter("status", ExpenseStatus.RECORDED);
        query.setParameter("from", from);
        query.setParameter("to", to);
        if (vendorId != null) {
            query.setParameter("vendorId", vendorId);
        }
        return query.getResultList();
    }

    /**
     * Invoice aggregates: {count, sumTotal, sumPaid, sumOutstanding}.
     * Null sums (no rows) are normalized by the caller.
     */
    public Object[] invoiceAggregates(List<UUID> orgIds, LocalDate from, LocalDate to,
                                      List<InvoiceStatus> statuses, UUID customerId) {
        String jpql = "SELECT COUNT(i), SUM(i.totalAmount), SUM(i.amountPaid), SUM(i.balanceDue) "
                + "FROM Invoice i "
                + "WHERE i.organization.id IN :orgIds "
                + "AND i.status IN :statuses "
                + "AND i.invoiceDate BETWEEN :from AND :to "
                + (customerId != null ? "AND i.customer.id = :customerId " : "");
        var query = entityManager.createQuery(jpql, Object[].class);
        query.setParameter("orgIds", orgIds);
        query.setParameter("statuses", statuses);
        query.setParameter("from", from);
        query.setParameter("to", to);
        if (customerId != null) {
            query.setParameter("customerId", customerId);
        }
        return query.getSingleResult();
    }

    /**
     * Per-customer invoice exposure for issued-type invoices in a date range.
     */
    public List<CustomerOutstanding> invoiceOutstandingByCustomer(List<UUID> orgIds, LocalDate from, LocalDate to,
                                                                 List<InvoiceStatus> statuses) {
        String jpql = "SELECT NEW com.example.accounting.report.CustomerOutstanding("
                + "c.id, c.name, COUNT(i), SUM(i.totalAmount), SUM(i.balanceDue)) "
                + "FROM Invoice i JOIN i.customer c "
                + "WHERE i.organization.id IN :orgIds "
                + "AND i.status IN :statuses "
                + "AND i.invoiceDate BETWEEN :from AND :to "
                + "GROUP BY c.id, c.name "
                + "ORDER BY c.name ASC";
        TypedQuery<CustomerOutstanding> query = entityManager.createQuery(jpql, CustomerOutstanding.class);
        query.setParameter("orgIds", orgIds);
        query.setParameter("statuses", statuses);
        query.setParameter("from", from);
        query.setParameter("to", to);
        return query.getResultList();
    }

    public long countActiveVendors(List<UUID> orgIds) {
        TypedQuery<Long> query = entityManager.createQuery(
                "SELECT COUNT(v) FROM Vendor v WHERE v.organization.id IN :orgIds AND v.active = true", Long.class);
        query.setParameter("orgIds", orgIds);
        return query.getSingleResult();
    }

    public long countActiveCustomers(List<UUID> orgIds) {
        TypedQuery<Long> query = entityManager.createQuery(
                "SELECT COUNT(c) FROM Customer c WHERE c.organization.id IN :orgIds AND c.active = true", Long.class);
        query.setParameter("orgIds", orgIds);
        return query.getSingleResult();
    }

    public long countRecordedExpenses(List<UUID> orgIds, LocalDate from, LocalDate to) {
        TypedQuery<Long> query = entityManager.createQuery(
                "SELECT COUNT(e) FROM Expense e WHERE e.organization.id IN :orgIds "
                        + "AND e.status = :status AND e.expenseDate BETWEEN :from AND :to", Long.class);
        query.setParameter("orgIds", orgIds);
        query.setParameter("status", ExpenseStatus.RECORDED);
        query.setParameter("from", from);
        query.setParameter("to", to);
        return query.getSingleResult();
    }
}
