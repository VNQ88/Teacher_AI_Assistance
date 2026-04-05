package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.common.enumerate.ExamStatus;
import com.example.teacherassistantai.entity.Exam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExamRepository extends JpaRepository<Exam, Long> {

    @Query("SELECT e FROM Exam e WHERE " +
            "(:classroomId IS NULL OR e.classroom.id = :classroomId) AND " +
            "(:status IS NULL OR e.status = :status)")
    Page<Exam> findByFilters(
            @Param("classroomId") Long classroomId,
            @Param("status") ExamStatus status,
            Pageable pageable
    );

    @Query("SELECT e FROM Exam e WHERE e.classroom.id = :classroomId AND " +
            "(:status IS NULL OR e.status = :status) " +
            "ORDER BY e.startTime DESC")
    Page<Exam> findByClassroomIdAndStatus(
            @Param("classroomId") Long classroomId,
            @Param("status") ExamStatus status,
            Pageable pageable
    );
}

