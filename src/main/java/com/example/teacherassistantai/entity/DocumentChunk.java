package com.example.teacherassistantai.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;

/**
 * Đoạn văn bản đã được chunk từ Document, dùng cho RAG (Retrieval-Augmented Generation).
 *
 * Thiết kế cho multi-subject:
 * - Lưu subjectId dạng denormalized để query nhanh khi filter theo môn
 *   (tránh JOIN qua Document → Subject mỗi lần tìm kiếm)
 * - externalVectorId: nếu dùng vector DB ngoài (Qdrant/Pinecone), lưu ID ở đây.
 *   Nếu dùng pgvector, embedding lưu trực tiếp tại đây (cần extension pgvector).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "document_chunks", indexes = {
        @Index(name = "idx_chunk_document", columnList = "document_id"),
        @Index(name = "idx_chunk_subject", columnList = "subject_id"),
        @Index(name = "idx_chunk_subject_classroom", columnList = "subject_id, classroom_id")
})
public class DocumentChunk extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    Document document;

    /**
     * Denormalized subject_id để filter nhanh khi RAG agent tìm kiếm.
     * Giá trị phải đồng bộ với document.subject.id khi tạo chunk.
     */
    @Column(name = "subject_id", nullable = false)
    Long subjectId;

    @Column(name = "classroom_id")
    Long classroomId;

    @Column(nullable = false)
    Integer chunkIndex; // Thứ tự chunk trong document

    @Column(length = 20, nullable = false)
    @Builder.Default
    String chunkType = "TEXT";

    @Column(columnDefinition = "TEXT", nullable = false)
    String content; // Nội dung đoạn văn bản

    @Column(length = 100)
    String contentHash; // MD5/SHA hash để phát hiện trùng lặp

    @Column(name = "page_from")
    Integer pageFrom;

    @Column(name = "page_to")
    Integer pageTo;

    @Column(name = "token_count")
    Integer tokenCount;

    /**
     * Metadata bổ sung dạng JSON (trang số, tiêu đề đoạn, loại nội dung...).
     * Ví dụ: {"page": 5, "section": "Chapter 2 - Vocabulary", "topic": "Past Tense"}
     */
    @Column(columnDefinition = "TEXT")
    String metadata;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_jsonb", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    Map<String, Object> metadataJsonb = new HashMap<>();
}
