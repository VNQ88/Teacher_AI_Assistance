package com.example.teacherassistantai.entity;

import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.common.enumerate.DocumentEnrichmentStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Tài liệu học tập được upload lên hệ thống.
 * Gắn với Subject (bắt buộc) và tùy chọn gắn thêm Classroom cụ thể.
 *
 * Luồng: Upload → chunk text → tạo embedding → lưu DocumentChunk → status = READY
 * RAG agent sẽ filter theo subject_id khi tìm kiếm để tránh nhầm lẫn giữa các môn.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "documents")
public class Document extends BaseEntity {

    @Column(length = 255, nullable = false)
    String title;

    @Column(columnDefinition = "TEXT")
    String description;

    @Column(length = 500, nullable = false, unique = true)
    String originalObjectKey; // Key object file goc trong MinIO/S3

    @Column(length = 500, unique = true)
    String markdownObjectKey; // Key object file markdown trong MinIO/S3 (co the null khi chua parse xong)

    @Column(length = 500, unique = true)
    String hierarchyObjectKey;

    @Column(length = 500, unique = true)
    String chunksObjectKey;

    @Column(length = 20, nullable = false)
    String fileType; // "PDF", "DOCX", "TXT"

    @Column(nullable = false)
    @Builder.Default
    Long fileSizeBytes = 0L;

    /**
     * Môn học mà tài liệu này thuộc về.
     * Khi RAG agent truy vấn, luôn filter theo subject_id
     * để đảm bảo chỉ dùng tài liệu đúng môn.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_id", nullable = false)
    Subject subject;

    /**
     * Lớp học cụ thể (nullable).
     * Nếu null → tài liệu dùng chung cho toàn môn học.
     * Nếu có → tài liệu chỉ dành riêng cho lớp đó.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "classroom_id")
    Classroom classroom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", nullable = false)
    User uploadedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    DocumentStatus status = DocumentStatus.UPLOADED;

    @Enumerated(EnumType.STRING)
    @Column(name = "enrichment_status", nullable = false, length = 30,
            columnDefinition = "VARCHAR(30) DEFAULT 'NOT_STARTED'")
    @Builder.Default
    DocumentEnrichmentStatus enrichmentStatus = DocumentEnrichmentStatus.NOT_STARTED;

    @Column(name = "enrichment_started_at")
    LocalDateTime enrichmentStartedAt;

    @Column(name = "enrichment_completed_at")
    LocalDateTime enrichmentCompletedAt;

    @Column(name = "enrichment_error", columnDefinition = "TEXT")
    String enrichmentError;

    @Column(name = "enrichment_retry_count", nullable = false, columnDefinition = "INTEGER DEFAULT 0")
    @Builder.Default
    Integer enrichmentRetryCount = 0;

    @Column(columnDefinition = "TEXT")
    String processingError; // Lưu lỗi nếu status = FAILED

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    List<DocumentChunk> chunks = new ArrayList<>();

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    List<DocumentNode> nodes = new ArrayList<>();
}
