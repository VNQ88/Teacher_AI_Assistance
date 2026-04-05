package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    Optional<Document> findByOriginalObjectKey(String originalObjectKey);

    Optional<Document> findByMarkdownObjectKey(String markdownObjectKey);

    @Query("SELECT d FROM Document d WHERE " +
            "(:subjectId IS NULL OR d.subject.id = :subjectId) AND " +
            "(:status IS NULL OR d.status = :status)")
    Page<Document> findByFilters(@Param("subjectId") Long subjectId,
                                 @Param("status") DocumentStatus status,
                                 Pageable pageable);
}
