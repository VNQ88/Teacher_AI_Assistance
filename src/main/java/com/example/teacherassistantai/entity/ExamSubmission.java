package com.example.teacherassistantai.entity;

import com.example.teacherassistantai.common.enumerate.SubmissionStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Bài làm của học sinh cho một kỳ thi.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "exam_submissions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"exam_id", "student_id"})
})
public class ExamSubmission extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_id", nullable = false)
    Exam exam;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    User student;

    @Column
    LocalDateTime startedAt;

    @Column
    LocalDateTime submittedAt;

    @Column
    @Builder.Default
    Double totalScore = 0.0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    SubmissionStatus status = SubmissionStatus.IN_PROGRESS;

    /**
     * Người/agent đã chấm điểm cuối cùng (nullable nếu chưa chấm).
     * Có thể là giáo viên hoặc null nếu AI tự chấm và không cần review.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "graded_by")
    User gradedBy;

    @Column
    LocalDateTime gradedAt;

    @Column(columnDefinition = "TEXT")
    String teacherComment; // Nhận xét tổng thể của giáo viên

    @OneToMany(mappedBy = "submission", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    List<StudentAnswer> studentAnswers = new ArrayList<>();
}
