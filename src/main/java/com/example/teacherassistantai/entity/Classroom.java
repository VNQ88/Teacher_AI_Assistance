package com.example.teacherassistantai.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lớp học — gắn với 1 môn học cụ thể và 1 giáo viên phụ trách.
 * Học sinh tham gia lớp để truy cập tài liệu, làm bài thi của môn đó.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "classrooms")
public class Classroom extends BaseEntity {

    @Column(length = 100, nullable = false)
    String name; // Ví dụ: "Tiếng Anh 10A1 - HK1 2025-2026"

    @Column(length = 20)
    String code; // Ví dụ: "EN10A1-HK1"

    @Column(length = 20, nullable = false)
    String academicYear; // "2025-2026"

    @Column(length = 20)
    String semester; // "HK1", "HK2"

    @Column(columnDefinition = "TEXT")
    String description;

    @Column(nullable = false)
    @Builder.Default
    Boolean active = true;

    /**
     * Môn học của lớp này.
     * Key design: mỗi Classroom chỉ thuộc 1 Subject.
     * → RAG agent chỉ tìm kiếm trong DocumentChunk của Subject đó → không bị nhầm môn.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_id", nullable = false)
    Subject subject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id", nullable = false)
    User teacher;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_classroom",
            joinColumns = @JoinColumn(name = "classroom_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    @Builder.Default
    Set<User> students = new HashSet<>();

    @OneToMany(mappedBy = "classroom", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    List<Exam> exams = new ArrayList<>();
}
