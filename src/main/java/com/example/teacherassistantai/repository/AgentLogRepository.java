package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.entity.AgentLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentLogRepository extends JpaRepository<AgentLog, Long> {
    @Modifying
    @Query(value = """
            DELETE FROM agent_logs
            WHERE subject_id = :subjectId
               OR (
                    related_entity_type = 'Subject'
                    AND related_entity_id = :subjectId
               )
               OR (
                    related_entity_type = 'ChatSession'
                    AND related_entity_id IN (
                        SELECT id FROM chat_sessions WHERE subject_id = :subjectId
                    )
               )
               OR (
                    related_entity_type = 'Document'
                    AND related_entity_id IN (
                        SELECT id FROM documents WHERE subject_id = :subjectId
                    )
               )
            """, nativeQuery = true)
    void deleteBySubjectId(@Param("subjectId") Long subjectId);

    @Modifying
    @Query(value = """
            DELETE FROM agent_logs
            WHERE triggered_by = :userId
               OR (
                    related_entity_type = 'User'
                    AND related_entity_id = :userId
               )
               OR subject_id IN (
                    SELECT id FROM subjects WHERE owner_id = :userId
               )
               OR (
                    related_entity_type = 'Subject'
                    AND related_entity_id IN (
                        SELECT id FROM subjects WHERE owner_id = :userId
                    )
               )
               OR (
                    related_entity_type = 'ChatSession'
                    AND related_entity_id IN (
                        SELECT id
                        FROM chat_sessions
                        WHERE user_id = :userId
                           OR subject_id IN (
                                SELECT id FROM subjects WHERE owner_id = :userId
                           )
                    )
               )
               OR (
                    related_entity_type = 'Document'
                    AND related_entity_id IN (
                        SELECT id
                        FROM documents
                        WHERE uploaded_by = :userId
                           OR subject_id IN (
                                SELECT id FROM subjects WHERE owner_id = :userId
                           )
                    )
               )
            """, nativeQuery = true)
    void deleteByUserId(@Param("userId") Long userId);
}
