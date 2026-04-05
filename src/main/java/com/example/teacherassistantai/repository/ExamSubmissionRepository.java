package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.common.enumerate.SubmissionStatus;
import com.example.teacherassistantai.entity.ExamSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExamSubmissionRepository extends JpaRepository<ExamSubmission, Long> {

    boolean existsByExamIdAndStudentId(Long examId, Long studentId);

    Optional<ExamSubmission> findByExamIdAndStudentId(Long examId, Long studentId);

    List<ExamSubmission> findByExamId(Long examId);

    @Query("SELECT s FROM ExamSubmission s WHERE s.exam.id = :examId " +
            "AND (:status IS NULL OR s.status = :status)")
    List<ExamSubmission> findByExamIdAndStatus(
            @Param("examId") Long examId,
            @Param("status") SubmissionStatus status);
}

