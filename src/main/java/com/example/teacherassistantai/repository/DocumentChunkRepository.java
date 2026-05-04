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

    List<DocumentChunk> findByDocumentIdAndChunkTypeOrderBySourceOrderAsc(Long documentId, String chunkType);

    List<DocumentChunk> findByDocumentIdAndParentNodeIdInOrderBySourceOrderAsc(Long documentId, List<Long> parentNodeIds);

    @Query(value = """
            WITH RECURSIVE node_tree AS (
                SELECT id
                FROM document_nodes
                WHERE id = :rootNodeId
                UNION ALL
                SELECT child.id
                FROM document_nodes child
                JOIN node_tree parent ON child.parent_id = parent.id
            )
            SELECT dc.*
            FROM document_chunks dc
            WHERE dc.node_id IN (SELECT id FROM node_tree)
            ORDER BY COALESCE(dc.source_order, dc.chunk_index, 2147483647), dc.id
            """, nativeQuery = true)
    List<DocumentChunk> findScopeChunksByRootNodeId(@Param("rootNodeId") Long rootNodeId);

    @Query(value = """
            SELECT *
            FROM document_chunks dc
            WHERE dc.subject_id = :subjectId
              AND dc.embedding IS NOT NULL
              AND (
                    :sectionNumber IS NULL
                    OR lower(dc.content) LIKE ('%phan ' || :sectionNumber || '%')
                    OR lower(dc.content) LIKE ('%phần ' || :sectionNumber || '%')
                    OR lower(dc.content) LIKE ('%chuong ' || :sectionNumber || '%')
                    OR lower(dc.content) LIKE ('%chương ' || :sectionNumber || '%')
                    OR lower(dc.content) LIKE ('%muc ' || :sectionNumber || '%')
                    OR lower(dc.content) LIKE ('%mục ' || :sectionNumber || '%')
                    OR lower(dc.content) LIKE ('%section ' || :sectionNumber || '%')
                    OR lower(dc.content) LIKE ('%chapter ' || :sectionNumber || '%')
                    OR lower(dc.content) LIKE ('%part ' || :sectionNumber || '%')
                    OR lower(COALESCE(dc.section_path, '')) LIKE ('%phan ' || :sectionNumber || '%')
                    OR lower(COALESCE(dc.section_path, '')) LIKE ('%phần ' || :sectionNumber || '%')
                    OR lower(COALESCE(dc.section_path, '')) LIKE ('%chuong ' || :sectionNumber || '%')
                    OR lower(COALESCE(dc.section_path, '')) LIKE ('%chương ' || :sectionNumber || '%')
                    OR lower(COALESCE(dc.section_path, '')) LIKE ('%muc ' || :sectionNumber || '%')
                    OR lower(COALESCE(dc.section_path, '')) LIKE ('%mục ' || :sectionNumber || '%')
                    OR lower(COALESCE(dc.section_path, '')) LIKE ('%section ' || :sectionNumber || '%')
                    OR lower(COALESCE(dc.section_path, '')) LIKE ('%chapter ' || :sectionNumber || '%')
                    OR lower(COALESCE(dc.section_path, '')) LIKE ('%part ' || :sectionNumber || '%')
                  )
              AND dc.metadata_jsonb IS NOT NULL
              AND (dc.metadata_jsonb ->> 'charCount') ~ '^[0-9]+$'
              AND ((dc.metadata_jsonb ->> 'charCount')::int) >= :minCharCount
            ORDER BY dc.embedding <=> CAST(:queryEmbedding AS vector)
            LIMIT :candidateTopK
            """, nativeQuery = true)
    List<DocumentChunk> searchBySubjectVector(@Param("subjectId") Long subjectId,
                                              @Param("queryEmbedding") String queryEmbedding,
                                              @Param("minCharCount") int minCharCount,
                                              @Param("candidateTopK") int candidateTopK,
                                              @Param("sectionNumber") Integer sectionNumber);

    @Query(value = """
            UPDATE document_chunks
            SET embedding = CAST(:embedding AS vector)
            WHERE id = :chunkId
            """, nativeQuery = true)
    @org.springframework.data.jpa.repository.Modifying
    void updateEmbeddingLiteral(@Param("chunkId") Long chunkId,
                                @Param("embedding") String embedding);
}
