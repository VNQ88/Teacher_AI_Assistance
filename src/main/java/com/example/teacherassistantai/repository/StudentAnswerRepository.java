package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.entity.StudentAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudentAnswerRepository extends JpaRepository<StudentAnswer, Long> {

    List<StudentAnswer> findBySubmissionId(Long submissionId);

    void deleteByExamQuestionIdIn(List<Long> examQuestionIds);
}

