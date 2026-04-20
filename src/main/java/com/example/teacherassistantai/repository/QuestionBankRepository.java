package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.entity.QuestionBank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QuestionBankRepository extends JpaRepository<QuestionBank, Long> {

    List<QuestionBank> findBySubject_Id(Long subjectId);

    @Query("SELECT qb FROM QuestionBank qb WHERE " +
            "(:subjectId IS NULL OR qb.subject.id = :subjectId) AND " +
            "(:published IS NULL OR qb.published = :published)")
    Page<QuestionBank> findByFilters(
            @Param("subjectId") Long subjectId,
            @Param("published") Boolean published,
            Pageable pageable
    );
}

