package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {
    @Modifying
    @Query(value = """
            DELETE FROM message_source_chunks
            WHERE chunk_id IN (
                SELECT id FROM document_chunks WHERE document_id = :documentId
            )
            """, nativeQuery = true)
    void deleteMessageSourceLinksByDocumentId(@Param("documentId") Long documentId);

    void deleteByDocumentId(Long documentId);

    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    @Query(value = """
            SELECT *
            FROM document_chunks dc
            WHERE dc.subject_id = :subjectId
              AND dc.embedding IS NOT NULL
              AND dc.chunk_type = 'TEXT'
              AND (
                    :sectionNumber IS NULL
                    OR lower(dc.content) LIKE ('%phan ' || :sectionNumber || '%')
                    OR lower(dc.content) LIKE ('%chuong ' || :sectionNumber || '%')
                    OR lower(dc.content) LIKE ('%muc ' || :sectionNumber || '%')
                    OR lower(dc.content) LIKE ('%section ' || :sectionNumber || '%')
                    OR lower(dc.content) LIKE ('%chapter ' || :sectionNumber || '%')
                    OR lower(dc.content) LIKE ('%part ' || :sectionNumber || '%')
                  )
              AND dc.metadata_jsonb IS NOT NULL
              AND COALESCE(dc.metadata_jsonb ->> 'chunkType', 'TEXT') = 'TEXT'
              AND (dc.metadata_jsonb ->> 'charCount') ~ '^[0-9]+$'
              AND ((dc.metadata_jsonb ->> 'charCount')::int) >= :minCharCount
            ORDER BY dc.embedding <=> CAST(:queryEmbedding AS halfvec)
            LIMIT :candidateTopK
            """, nativeQuery = true)
    List<DocumentChunk> searchBySubjectVector(@Param("subjectId") Long subjectId,
                                              @Param("queryEmbedding") String queryEmbedding,
                                              @Param("minCharCount") int minCharCount,
                                              @Param("candidateTopK") int candidateTopK,
                                              @Param("sectionNumber") Integer sectionNumber);

    @Query(value = """
            UPDATE document_chunks
            SET embedding = CAST(:embedding AS halfvec)
            WHERE id = :chunkId
            """, nativeQuery = true)
    @org.springframework.data.jpa.repository.Modifying
    void updateEmbeddingLiteral(@Param("chunkId") Long chunkId,
                                @Param("embedding") String embedding);
}
