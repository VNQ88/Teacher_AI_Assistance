package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.entity.ChatMessage;
import com.example.teacherassistantai.common.enumerate.MessageRole;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    Page<ChatMessage> findBySessionIdOrderByCreatedAtDesc(Long sessionId, Pageable pageable);

    @EntityGraph(attributePaths = "sourceChunks")
    Optional<ChatMessage> findTopBySessionIdAndRoleAndIdLessThanOrderByIdDesc(
            Long sessionId, MessageRole role, Long id);

    @Modifying
    @Query(value = """
            DELETE FROM message_source_chunks
            WHERE message_id IN (
                SELECT id FROM chat_messages WHERE session_id = :sessionId
            )
            """, nativeQuery = true)
    void deleteMessageSourceLinksBySessionId(@Param("sessionId") Long sessionId);

    void deleteBySessionId(Long sessionId);
}
