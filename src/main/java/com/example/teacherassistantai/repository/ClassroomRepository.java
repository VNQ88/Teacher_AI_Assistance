package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.entity.Classroom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ClassroomRepository extends JpaRepository<Classroom, Long> {
    boolean existsByCodeIgnoreCase(String code);

    List<Classroom> findBySubjectId(Long subjectId);

    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END " +
            "FROM Classroom c JOIN c.students s " +
            "WHERE c.id = :classroomId AND s.id = :userId")
    boolean isStudentInClassroom(@Param("classroomId") Long classroomId, @Param("userId") Long userId);
}

