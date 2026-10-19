package com.example.accounting.repository;

import com.example.accounting.entity.InvoiceLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InvoiceLineRepository extends JpaRepository<InvoiceLine, UUID> {

    List<InvoiceLine> findByInvoiceId(UUID invoiceId);

    void deleteByInvoiceId(UUID invoiceId);
}
