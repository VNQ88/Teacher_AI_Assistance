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

    @Modifying
    @Query(value = """
            DELETE FROM message_source_chunks
            WHERE chunk_id IN (
                SELECT id FROM document_chunks WHERE subject_id = :subjectId
            )
            """, nativeQuery = true)
    void deleteMessageSourceLinksBySubjectId(@Param("subjectId") Long subjectId);

    @Modifying
    @Query(value = "DELETE FROM document_chunks WHERE subject_id = :subjectId", nativeQuery = true)
    void deleteBySubjectId(@Param("subjectId") Long subjectId);

    @Modifying
    @Query(value = """
            DELETE FROM message_source_chunks
            WHERE chunk_id IN (
                SELECT dc.id
                FROM document_chunks dc
                JOIN documents d ON dc.document_id = d.id
                WHERE d.uploaded_by = :userId
                   OR d.subject_id IN (
                        SELECT id FROM subjects WHERE owner_id = :userId
                   )
            )
            """, nativeQuery = true)
    void deleteMessageSourceLinksByUserId(@Param("userId") Long userId);

    @Modifying
    @Query(value = """
            DELETE FROM document_chunks
            WHERE document_id IN (
                SELECT id
                FROM documents
                WHERE uploaded_by = :userId
                   OR subject_id IN (
                        SELECT id FROM subjects WHERE owner_id = :userId
                   )
            )
            """, nativeQuery = true)
    void deleteByUserId(@Param("userId") Long userId);

    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    List<DocumentChunk> findByDocumentIdAndChunkTypeOrderBySourceOrderAsc(Long documentId, String chunkType);

    List<DocumentChunk> findByDocumentIdAndParentNodeIdInOrderBySourceOrderAsc(Long documentId, List<Long> parentNodeIds);

    List<DocumentChunk> findByNodeIdOrderBySourceOrderAsc(Long nodeId);

    List<DocumentChunk> findByDocumentIdAndNodeIdOrderBySourceOrderAsc(Long documentId, Long nodeId);

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
              AND upper(COALESCE(dc.chunk_type, 'TEXT')) <> 'CITATION'
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
              AND dc.subject_id = :subjectId
              AND dc.embedding IS NOT NULL
              AND upper(COALESCE(dc.chunk_type, 'TEXT')) <> 'CITATION'
              AND dc.metadata_jsonb IS NOT NULL
              AND (dc.metadata_jsonb ->> 'charCount') ~ '^[0-9]+$'
              AND ((dc.metadata_jsonb ->> 'charCount')::int) >= :minCharCount
            ORDER BY dc.embedding <=> CAST(:queryEmbedding AS vector)
            LIMIT :candidateTopK
            """, nativeQuery = true)
    List<DocumentChunk> searchByNodeSubtreeVector(@Param("subjectId") Long subjectId,
                                                  @Param("rootNodeId") Long rootNodeId,
                                                  @Param("queryEmbedding") String queryEmbedding,
                                                  @Param("minCharCount") int minCharCount,
                                                  @Param("candidateTopK") int candidateTopK);

    @Query(value = """
            UPDATE document_chunks
            SET embedding = CAST(:embedding AS vector)
            WHERE id = :chunkId
            """, nativeQuery = true)
    @org.springframework.data.jpa.repository.Modifying
    void updateEmbeddingLiteral(@Param("chunkId") Long chunkId,
                                @Param("embedding") String embedding);
}
