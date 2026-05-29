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
}
