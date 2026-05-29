package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.common.enumerate.DocumentEnrichmentStatus;
import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    Optional<Document> findByOriginalObjectKey(String originalObjectKey);

    Optional<Document> findByMarkdownObjectKey(String markdownObjectKey);

    @Query("""
            SELECT d.id AS id,
                   d.originalObjectKey AS originalObjectKey,
                   d.markdownObjectKey AS markdownObjectKey,
                   d.hierarchyObjectKey AS hierarchyObjectKey,
                   d.chunksObjectKey AS chunksObjectKey
            FROM Document d
            WHERE d.subject.id = :subjectId
            """)
    List<DocumentStorageObject> findStorageObjectsBySubjectId(@Param("subjectId") Long subjectId);

    @Modifying
    @Query(value = "DELETE FROM documents WHERE subject_id = :subjectId", nativeQuery = true)
    void deleteBySubjectId(@Param("subjectId") Long subjectId);

    @Query("""
            SELECT d.id AS id,
                   d.originalObjectKey AS originalObjectKey,
                   d.markdownObjectKey AS markdownObjectKey,
                   d.hierarchyObjectKey AS hierarchyObjectKey,
                   d.chunksObjectKey AS chunksObjectKey
            FROM Document d
            WHERE d.uploadedBy.id = :userId
               OR d.subject.ownerId = :userId
            """)
    List<DocumentStorageObject> findStorageObjectsForUserDeletion(@Param("userId") Long userId);

    @Modifying
    @Query(value = """
            DELETE FROM documents
            WHERE uploaded_by = :userId
               OR subject_id IN (
                    SELECT id FROM subjects WHERE owner_id = :userId
               )
            """, nativeQuery = true)
    void deleteByUserId(@Param("userId") Long userId);

    @Query("SELECT d FROM Document d WHERE " +
            "(:subjectId IS NULL OR d.subject.id = :subjectId) AND " +
            "(:status IS NULL OR d.status = :status)")
    Page<Document> findByFilters(@Param("subjectId") Long subjectId,
                                 @Param("status") DocumentStatus status,
                                 Pageable pageable);

    @Query("SELECT d FROM Document d WHERE " +
           "d.enrichmentStatus = :status AND " +
           "d.enrichmentCompletedAt < :cutoff AND " +
           "d.enrichmentRetryCount < :maxRetries")
    List<Document> findRetryableDocuments(
            @Param("status") DocumentEnrichmentStatus status,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("maxRetries") int maxRetries);

    interface DocumentStorageObject {
        Long getId();

        String getOriginalObjectKey();

        String getMarkdownObjectKey();

        String getHierarchyObjectKey();

        String getChunksObjectKey();
    }
}
