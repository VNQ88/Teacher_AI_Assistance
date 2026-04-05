package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {
    void deleteByDocumentId(Long documentId);

    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    @Query(value = """
            SELECT *
            FROM document_chunks dc
            WHERE dc.subject_id = :subjectId
              AND (:classroomId IS NULL OR dc.classroom_id = :classroomId OR dc.classroom_id IS NULL)
              AND dc.embedding IS NOT NULL
              AND dc.chunk_type = 'TEXT'
              AND dc.metadata_jsonb IS NOT NULL
              AND COALESCE(dc.metadata_jsonb ->> 'chunkType', 'TEXT') = 'TEXT'
              AND (dc.metadata_jsonb ->> 'charCount') ~ '^[0-9]+$'
              AND ((dc.metadata_jsonb ->> 'charCount')::int) >= :minCharCount
            ORDER BY dc.embedding <=> CAST(:queryEmbedding AS halfvec)
            LIMIT :candidateTopK
            """, nativeQuery = true)
    List<DocumentChunk> searchBySubjectVector(@Param("subjectId") Long subjectId,
                                              @Param("classroomId") Long classroomId,
                                              @Param("queryEmbedding") String queryEmbedding,
                                              @Param("minCharCount") int minCharCount,
                                              @Param("candidateTopK") int candidateTopK);

    @Query(value = """
            UPDATE document_chunks
            SET embedding = CAST(:embedding AS halfvec)
            WHERE id = :chunkId
            """, nativeQuery = true)
    @org.springframework.data.jpa.repository.Modifying
    void updateEmbeddingLiteral(@Param("chunkId") Long chunkId,
                                @Param("embedding") String embedding);
}
