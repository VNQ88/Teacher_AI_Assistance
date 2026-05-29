package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.entity.ChatSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    @Query("SELECT cs FROM ChatSession cs WHERE cs.user.id = :userId AND (:subjectId IS NULL OR cs.subject.id = :subjectId) ORDER BY cs.updatedAt DESC")
    Page<ChatSession> findByUserAndSubject(@Param("userId") Long userId,
                                           @Param("subjectId") Long subjectId,
                                           Pageable pageable);

    Optional<ChatSession> findByIdAndUserId(Long id, Long userId);

    @Modifying
    @Query(value = "DELETE FROM chat_sessions WHERE subject_id = :subjectId", nativeQuery = true)
    void deleteBySubjectId(@Param("subjectId") Long subjectId);

    @Modifying
    @Query(value = """
            DELETE FROM chat_sessions
            WHERE user_id = :userId
               OR subject_id IN (
                    SELECT id FROM subjects WHERE owner_id = :userId
               )
            """, nativeQuery = true)
    void deleteByUserId(@Param("userId") Long userId);
}
