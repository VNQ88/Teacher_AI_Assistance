package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.entity.DocumentNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentNodeRepository extends JpaRepository<DocumentNode, Long> {

    @Modifying
    @Query(value = "DELETE FROM document_nodes WHERE document_id = :documentId", nativeQuery = true)
    void deleteByDocumentId(@Param("documentId") Long documentId);

    @Modifying
    @Query(value = "DELETE FROM document_nodes WHERE subject_id = :subjectId", nativeQuery = true)
    void deleteBySubjectId(@Param("subjectId") Long subjectId);

    @Modifying
    @Query(value = """
            DELETE FROM document_nodes
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

    List<DocumentNode> findByDocumentIdOrderByOrderIndexAsc(Long documentId);

    List<DocumentNode> findByDocumentIdAndParentIsNullOrderByOrderIndexAsc(Long documentId);

    Optional<DocumentNode> findByDocumentIdAndNodeKey(Long documentId, String nodeKey);

    List<DocumentNode> findByIdIn(Collection<Long> ids);

    List<DocumentNode> findByDocumentIdAndParentIdInOrderByOrderIndexAsc(Long documentId, Collection<Long> parentIds);

    List<DocumentNode> findByDocumentIdAndNodeTypeInOrderByOrderIndexAsc(Long documentId, Collection<String> nodeTypes);

    List<DocumentNode> findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(Long documentId, String nodeType);

    List<DocumentNode> findByParentIdOrderByOrderIndexAsc(Long parentId);

    List<DocumentNode> findByParentIdAndNodeTypeOrderByOrderIndexAsc(Long parentId, String nodeType);

    List<DocumentNode> findBySubjectIdAndNodeTypeInOrderByOrderIndexAsc(Long subjectId, Collection<String> nodeTypes);
}
