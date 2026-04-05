package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.entity.ExamQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExamQuestionRepository extends JpaRepository<ExamQuestion, Long> {

    List<ExamQuestion> findByExamIdOrderByOrderIndex(Long examId);

    boolean existsByExamIdAndQuestionId(Long examId, Long questionId);

    Optional<ExamQuestion> findByExamIdAndId(Long examId, Long examQuestionId);

    @Query("SELECT COUNT(eq) FROM ExamQuestion eq WHERE eq.exam.id = :examId")
    int countByExamId(@Param("examId") Long examId);
}

