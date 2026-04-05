package com.example.teacherassistantai.entity;

import com.example.teacherassistantai.common.enumerate.SubjectType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.ArrayList;
import java.util.List;

/**
 * Môn học — trung tâm phân loại toàn bộ tài liệu, lớp học, câu hỏi.
 * Giúp hệ thống hỗ trợ nhiều môn học (Tiếng Anh, Toán, Văn, ...)
 * mà không bị trộn lẫn kiến thức khi RAG agent trả lời.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "subjects")
public class Subject extends BaseEntity {

    @Column(length = 100, nullable = false, unique = true)
    String name; // Ví dụ: "Tiếng Anh", "Toán", "Văn học"

    @Column(length = 20, nullable = false, unique = true)
    String code; // Ví dụ: "EN", "MATH", "LIT"

    @Column(columnDefinition = "TEXT")
    String description;

    /**
     * Phân loại môn học để quyết định chiến lược xử lý của AI agent.
     * TEXT_BASED: dùng RAG thuần, phân tích ngôn ngữ.
     * STEM: có thể cần thêm công cụ tính toán, LaTeX rendering.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    SubjectType subjectType;

    @Column(nullable = false)
    @Builder.Default
    Boolean active = true;

    // Một Subject có nhiều Classroom
    @OneToMany(mappedBy = "subject", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    List<Classroom> classrooms = new ArrayList<>();

    // Một Subject có nhiều Document (tài liệu chính thống của môn)
    @OneToMany(mappedBy = "subject", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    List<Document> documents = new ArrayList<>();
}
