package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentNodeArtifactRepository extends JpaRepository<DocumentNodeArtifact, Long> {

    interface RetrievalEmbeddingState {
        String getRetrievalTextHash();

        String getEmbeddingModel();

        Integer getEmbeddingDimensions();

        Boolean getHasEmbedding();
    }

    interface CoarseNodeHit {
        Long getArtifactId();

        Long getNodeId();

        Long getDocumentId();

        String getDocumentTitle();

        String getNodeType();

        String getSectionPath();

        Double getDistance();
    }

    interface RetrievalEmbeddingCoverageStats {
        Long getTotalCompletedSummaries();

        Long getEmbeddedCurrent();

        Long getPending();
    }

    Optional<DocumentNodeArtifact> findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
            Long documentNodeId,
            DocumentNodeArtifactType artifactType,
            String promptVersion,
            String model,
            String sourceHash
    );

    @Query("""
            SELECT a
            FROM DocumentNodeArtifact a
            JOIN a.documentNode n
            WHERE a.document.id = :documentId
            ORDER BY n.orderIndex ASC, a.artifactType ASC
            """)
    List<DocumentNodeArtifact> findByDocumentIdOrderByNodeOrderAndArtifactType(@Param("documentId") Long documentId);

    List<DocumentNodeArtifact> findByDocumentIdAndStatusOrderByUpdatedAtDesc(
            Long documentId,
            DocumentNodeArtifactStatus status
    );

    @Query("""
            SELECT COUNT(a) > 0
            FROM DocumentNodeArtifact a
            WHERE a.document.id = :documentId
              AND a.artifactType = :artifactType
              AND a.status IN :statuses
            """)
    boolean existsByDocumentIdAndArtifactTypeAndStatusIn(@Param("documentId") Long documentId,
                                                         @Param("artifactType") DocumentNodeArtifactType artifactType,
                                                         @Param("statuses") Collection<DocumentNodeArtifactStatus> statuses);

    List<DocumentNodeArtifact> findByDocumentNodeIdOrderByArtifactTypeAscUpdatedAtDesc(Long documentNodeId);

    @Modifying
    void deleteByDocumentId(Long documentId);

    @Modifying
    @Query(value = """
            DELETE FROM document_node_artifacts
            WHERE document_id IN (
                SELECT id FROM documents WHERE subject_id = :subjectId
            )
            """, nativeQuery = true)
    void deleteBySubjectId(@Param("subjectId") Long subjectId);

    @Modifying
    @Query(value = """
            DELETE FROM document_node_artifacts
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

    @Modifying
    void deleteByDocumentIdAndArtifactTypeIn(Long documentId, Collection<DocumentNodeArtifactType> artifactTypes);

    @Modifying
    void deleteByDocumentNodeIdAndArtifactTypeIn(Long documentNodeId, Collection<DocumentNodeArtifactType> artifactTypes);

    @Query("""
            SELECT a
            FROM DocumentNodeArtifact a
            WHERE a.documentNode.id = :documentNodeId
              AND a.artifactType = :artifactType
              AND a.status = :status
            ORDER BY a.updatedAt DESC
            """)
    List<DocumentNodeArtifact> findLatestByNodeTypeAndStatus(@Param("documentNodeId") Long documentNodeId,
                                                             @Param("artifactType") DocumentNodeArtifactType artifactType,
                                                             @Param("status") DocumentNodeArtifactStatus status);

    Optional<DocumentNodeArtifact> findFirstByDocumentNodeIdAndArtifactTypeAndStatusOrderByUpdatedAtDesc(
            Long documentNodeId,
            DocumentNodeArtifactType artifactType,
            DocumentNodeArtifactStatus status
    );

    Optional<DocumentNodeArtifact> findFirstByDocumentNodeIdAndArtifactTypeAndStatusAndPromptVersionAndModelOrderByUpdatedAtDesc(
            Long documentNodeId,
            DocumentNodeArtifactType artifactType,
            DocumentNodeArtifactStatus status,
            String promptVersion,
            String model
    );

    default Optional<DocumentNodeArtifact> findLatestCompletedSummaryByNodeId(Long nodeId) {
        return findFirstByDocumentNodeIdAndArtifactTypeAndStatusOrderByUpdatedAtDesc(
                nodeId,
                DocumentNodeArtifactType.SUMMARY,
                DocumentNodeArtifactStatus.COMPLETED
        );
    }

    default Optional<DocumentNodeArtifact> findLatestCompletedSummaryByNodeId(Long nodeId,
                                                                              String promptVersion,
                                                                              String model) {
        return findFirstByDocumentNodeIdAndArtifactTypeAndStatusAndPromptVersionAndModelOrderByUpdatedAtDesc(
                nodeId,
                DocumentNodeArtifactType.SUMMARY,
                DocumentNodeArtifactStatus.COMPLETED,
                promptVersion,
                model
        );
    }

    @Query("""
            SELECT a
            FROM DocumentNodeArtifact a
            JOIN FETCH a.documentNode n
            JOIN FETCH n.document d
            WHERE a.status = com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus.RATE_LIMITED
            ORDER BY a.updatedAt ASC
            """)
    List<DocumentNodeArtifact> findRateLimitedOrderByUpdatedAtAsc();

    @Query("""
            SELECT a
            FROM DocumentNodeArtifact a
            JOIN FETCH a.documentNode n
            JOIN FETCH n.document d
            WHERE a.artifactType = :artifactType
              AND a.status = com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus.RATE_LIMITED
            ORDER BY a.updatedAt ASC
            """)
    List<DocumentNodeArtifact> findRateLimitedByArtifactTypeOrderByUpdatedAtAsc(
            @Param("artifactType") DocumentNodeArtifactType artifactType
    );

    @Query("""
            SELECT a
            FROM DocumentNodeArtifact a
            JOIN FETCH a.documentNode n
            WHERE n.id IN :nodeIds
              AND a.artifactType = com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType.SUMMARY
              AND a.status = com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus.COMPLETED
            ORDER BY n.orderIndex ASC, a.updatedAt DESC
            """)
    List<DocumentNodeArtifact> findCompletedSummariesByNodeIds(@Param("nodeIds") Collection<Long> nodeIds);

    @Query("""
            SELECT a
            FROM DocumentNodeArtifact a
            JOIN FETCH a.documentNode n
            JOIN FETCH a.document d
            WHERE n.id IN :nodeIds
              AND a.artifactType = :artifactType
              AND a.status = :status
            ORDER BY n.orderIndex ASC, a.updatedAt DESC
            """)
    List<DocumentNodeArtifact> findByNodeIdsAndArtifactTypeAndStatusOrderByNodeOrderAndUpdatedAt(
            @Param("nodeIds") Collection<Long> nodeIds,
            @Param("artifactType") DocumentNodeArtifactType artifactType,
            @Param("status") DocumentNodeArtifactStatus status
    );

    @Modifying
    @Query(value = """
            UPDATE document_node_artifacts
            SET retrieval_text = :retrievalText,
                retrieval_text_hash = :retrievalTextHash,
                embedding = CAST(:embedding AS vector),
                embedding_model = :embeddingModel,
                embedding_dimensions = :embeddingDimensions,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :artifactId
            """, nativeQuery = true)
    int updateRetrievalEmbedding(@Param("artifactId") Long artifactId,
                                 @Param("retrievalText") String retrievalText,
                                 @Param("retrievalTextHash") String retrievalTextHash,
                                 @Param("embedding") String embedding,
                                 @Param("embeddingModel") String embeddingModel,
                                 @Param("embeddingDimensions") int embeddingDimensions);

    @Modifying
    @Query(value = """
            UPDATE document_node_artifacts
            SET retrieval_text = NULL,
                retrieval_text_hash = NULL,
                embedding = NULL,
                embedding_model = NULL,
                embedding_dimensions = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :artifactId
            """, nativeQuery = true)
    int clearRetrievalEmbedding(@Param("artifactId") Long artifactId);

    @Query("""
            SELECT a
            FROM DocumentNodeArtifact a
            JOIN FETCH a.documentNode n
            JOIN FETCH a.document d
            WHERE a.id = :artifactId
              AND a.artifactType = com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType.SUMMARY
              AND a.status = com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus.COMPLETED
            """)
    Optional<DocumentNodeArtifact> findCompletedSummaryForRetrievalEmbedding(@Param("artifactId") Long artifactId);

    @Query(value = """
            SELECT retrieval_text_hash AS "retrievalTextHash",
                   embedding_model AS "embeddingModel",
                   embedding_dimensions AS "embeddingDimensions",
                   (embedding IS NOT NULL) AS "hasEmbedding"
            FROM document_node_artifacts
            WHERE id = :artifactId
            """, nativeQuery = true)
    Optional<RetrievalEmbeddingState> findRetrievalEmbeddingState(@Param("artifactId") Long artifactId);

    @Query(value = """
            SELECT id
            FROM document_node_artifacts
            WHERE artifact_type = 'SUMMARY'
              AND status = 'COMPLETED'
              AND (
                    embedding IS NULL
                 OR retrieval_text_hash IS NULL
                 OR embedding_model IS DISTINCT FROM :embeddingModel
                 OR embedding_dimensions IS DISTINCT FROM :embeddingDimensions
              )
            ORDER BY updated_at ASC
            LIMIT :batchSize
            """, nativeQuery = true)
    List<Long> findCompletedSummaryIdsNeedingRetrievalEmbedding(@Param("embeddingModel") String embeddingModel,
                                                                @Param("embeddingDimensions") int embeddingDimensions,
                                                                @Param("batchSize") int batchSize);

    @Query(value = """
            SELECT a.id
            FROM document_node_artifacts a
            JOIN documents d ON d.id = a.document_id
            WHERE a.artifact_type = 'SUMMARY'
              AND a.status = 'COMPLETED'
              AND (:documentId IS NULL OR a.document_id = :documentId)
              AND (:subjectId IS NULL OR d.subject_id = :subjectId)
              AND (
                    a.embedding IS NULL
                 OR a.retrieval_text_hash IS NULL
                 OR a.embedding_model IS DISTINCT FROM :embeddingModel
                 OR a.embedding_dimensions IS DISTINCT FROM :embeddingDimensions
              )
            ORDER BY a.updated_at ASC
            LIMIT :batchSize
            """, nativeQuery = true)
    List<Long> findCompletedSummaryIdsNeedingRetrievalEmbedding(@Param("documentId") Long documentId,
                                                                @Param("subjectId") Long subjectId,
                                                                @Param("embeddingModel") String embeddingModel,
                                                                @Param("embeddingDimensions") int embeddingDimensions,
                                                                @Param("batchSize") int batchSize);

    @Query(value = """
            SELECT COUNT(*) AS "totalCompletedSummaries",
                   COUNT(*) FILTER (
                       WHERE a.embedding IS NOT NULL
                         AND a.retrieval_text_hash IS NOT NULL
                         AND a.embedding_model = :embeddingModel
                         AND a.embedding_dimensions = :embeddingDimensions
                   ) AS "embeddedCurrent",
                   COUNT(*) FILTER (
                       WHERE a.embedding IS NULL
                          OR a.retrieval_text_hash IS NULL
                          OR a.embedding_model IS DISTINCT FROM :embeddingModel
                          OR a.embedding_dimensions IS DISTINCT FROM :embeddingDimensions
                   ) AS "pending"
            FROM document_node_artifacts a
            JOIN documents d ON d.id = a.document_id
            WHERE a.artifact_type = 'SUMMARY'
              AND a.status = 'COMPLETED'
              AND (:documentId IS NULL OR a.document_id = :documentId)
              AND (:subjectId IS NULL OR d.subject_id = :subjectId)
            """, nativeQuery = true)
    RetrievalEmbeddingCoverageStats retrievalEmbeddingCoverageStats(@Param("documentId") Long documentId,
                                                                    @Param("subjectId") Long subjectId,
                                                                    @Param("embeddingModel") String embeddingModel,
                                                                    @Param("embeddingDimensions") int embeddingDimensions);

    @Query(value = """
            SELECT a.id AS "artifactId",
                   n.id AS "nodeId",
                   d.id AS "documentId",
                   d.title AS "documentTitle",
                   n.node_type AS "nodeType",
                   n.section_path AS "sectionPath",
                   a.embedding <=> CAST(:queryEmbedding AS vector) AS "distance"
            FROM document_node_artifacts a
            JOIN document_nodes n ON n.id = a.document_node_id
            JOIN documents d ON d.id = a.document_id
            WHERE d.subject_id = :subjectId
              AND a.artifact_type = 'SUMMARY'
              AND a.status = 'COMPLETED'
              AND a.embedding IS NOT NULL
              AND a.embedding_model = :embeddingModel
              AND a.embedding_dimensions = :embeddingDimensions
              AND (:includeDocumentRoot = true OR n.node_type <> 'document')
              AND (:maxCoarseDistance <= 0 OR (a.embedding <=> CAST(:queryEmbedding AS vector)) <= :maxCoarseDistance)
            ORDER BY a.embedding <=> CAST(:queryEmbedding AS vector)
            LIMIT :coarseTopK
            """, nativeQuery = true)
    List<CoarseNodeHit> searchCompletedSummaryArtifactsVector(@Param("subjectId") Long subjectId,
                                                              @Param("queryEmbedding") String queryEmbedding,
                                                              @Param("embeddingModel") String embeddingModel,
                                                              @Param("embeddingDimensions") int embeddingDimensions,
                                                              @Param("includeDocumentRoot") boolean includeDocumentRoot,
                                                              @Param("maxCoarseDistance") double maxCoarseDistance,
                                                              @Param("coarseTopK") int coarseTopK);
}
