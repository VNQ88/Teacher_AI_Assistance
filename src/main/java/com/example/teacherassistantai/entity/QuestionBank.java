package com.example.teacherassistantai.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.ArrayList;
import java.util.List;

/**
 * Bộ câu hỏi — tập hợp các Question dùng để tạo đề thi hoặc ôn luyện.
 * Gắn với Subject để đảm bảo câu hỏi không bị dùng nhầm sang môn khác.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "question_banks")
public class QuestionBank extends BaseEntity {

    @Column(length = 255, nullable = false)
    String title;

    @Column(columnDefinition = "TEXT")
    String description;

    /**
     * Môn học của bộ câu hỏi này.
     * Khi tạo Exam, chỉ được chọn QuestionBank cùng Subject với Classroom.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_id", nullable = false)
    Subject subject;

    /**
     * Tài liệu gốc dùng để AI generate câu hỏi (nullable nếu tạo thủ công).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_document_id")
    Document sourceDocument;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    User createdBy;

    @Column(nullable = false)
    @Builder.Default
    Boolean published = false;

    @OneToMany(mappedBy = "questionBank", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    List<Question> questions = new ArrayList<>();
}
