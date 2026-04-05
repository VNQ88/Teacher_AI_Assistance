package com.example.teacherassistantai.entity;

import com.example.teacherassistantai.common.enumerate.ExamStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Bài kiểm tra/thi.
 * Gắn với Classroom (từ đó suy ra Subject).
 * Câu hỏi được chọn từ QuestionBank cùng Subject với Classroom.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "exams")
public class Exam extends BaseEntity {

    @Column(length = 255, nullable = false)
    String title;

    @Column(columnDefinition = "TEXT")
    String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "classroom_id", nullable = false)
    Classroom classroom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    User createdBy;

    @Column(nullable = false)
    LocalDateTime startTime;

    @Column(nullable = false)
    LocalDateTime endTime;

    @Column(nullable = false)
    Integer durationMinutes; // Thời gian làm bài (phút)

    @Column(nullable = false)
    @Builder.Default
    Double totalScore = 10.0;

    @Column(nullable = false)
    @Builder.Default
    Double passingScore = 5.0;

    /**
     * Shuffle thứ tự câu hỏi cho mỗi học sinh.
     */
    @Column(nullable = false)
    @Builder.Default
    Boolean shuffleQuestions = false;

    /**
     * Shuffle thứ tự đáp án cho câu MCQ.
     */
    @Column(nullable = false)
    @Builder.Default
    Boolean shuffleOptions = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    ExamStatus status = ExamStatus.DRAFT;

    @OneToMany(mappedBy = "exam", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    List<ExamQuestion> examQuestions = new ArrayList<>();

    @OneToMany(mappedBy = "exam", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    List<ExamSubmission> submissions = new ArrayList<>();
}
