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
        @Index(name = "idx_chunk_subject", columnList = "subject_id")
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id")
    DocumentNode node;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_node_id")
    DocumentNode parentNode;

    @Column(nullable = false)
    Integer chunkIndex; // Thứ tự chunk trong document

    @Column(name = "source_order")
    Integer sourceOrder;

    @Column(length = 20, nullable = false)
    @Builder.Default
    String chunkType = "TEXT";

    @Column(name = "section_path", columnDefinition = "TEXT")
    String sectionPath;

    @Column(name = "page_from")
    Integer pageFrom;

    @Column(name = "page_to")
    Integer pageTo;

    @Column(columnDefinition = "TEXT", nullable = false)
    String content; // Nội dung đoạn văn bản

    @Column(name = "embed_text", columnDefinition = "TEXT", nullable = false)
    @Builder.Default
    String embedText = "";

    @Column(name = "token_count")
    Integer tokenCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_jsonb", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    Map<String, Object> metadataJsonb = new HashMap<>();
}
