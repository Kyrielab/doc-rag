package com.zzx.docrag.ingest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Persistence port for ingestion state. */
public interface DocumentRepository extends JpaRepository<DocumentRecord, String> {

    List<DocumentRecord> findAllByOrderByCreatedAtDesc();

    long countByStatus(String status);
}
