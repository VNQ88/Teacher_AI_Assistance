package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.common.enumerate.DifficultyLevel;
import com.example.teacherassistantai.common.enumerate.QuestionType;
import com.example.teacherassistantai.entity.Question;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    void deleteByQuestionBankId(Long questionBankId);

    @Query("SELECT q.id FROM Question q WHERE q.questionBank.id = :questionBankId")
    List<Long> findIdsByQuestionBankId(@Param("questionBankId") Long questionBankId);
    
    @Query("SELECT q FROM Question q WHERE q.questionBank.id = :questionBankId AND " +
            "(:questionType IS NULL OR q.questionType = :questionType) AND " +
            "(:difficultyLevel IS NULL OR q.difficultyLevel = :difficultyLevel)")
    Page<Question> findByQuestionBankAndFilters(
            @Param("questionBankId") Long questionBankId,
            @Param("questionType") QuestionType questionType,
            @Param("difficultyLevel") DifficultyLevel difficultyLevel,
            Pageable pageable
    );
}

