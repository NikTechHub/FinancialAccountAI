package com.example.accounting.repository;

import com.example.accounting.entity.JournalEntryLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JournalEntryLineRepository extends JpaRepository<JournalEntryLine, UUID> {

    List<JournalEntryLine> findByJournalEntryId(UUID journalEntryId);

    void deleteByJournalEntryId(UUID journalEntryId);
}
