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

    List<DocumentNodeArtifact> findByDocumentNodeIdOrderByArtifactTypeAscUpdatedAtDesc(Long documentNodeId);

    @Modifying
    void deleteByDocumentId(Long documentId);

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
}
