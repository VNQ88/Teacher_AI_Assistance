package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.entity.Subject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubjectRepository extends JpaRepository<Subject, Long> {
    boolean existsByNameIgnoreCase(String name);

    boolean existsByCodeIgnoreCase(String code);

    @Modifying
    @Query(value = "DELETE FROM subjects WHERE owner_id = :ownerId", nativeQuery = true)
    void deleteByOwnerId(@Param("ownerId") Long ownerId);
}
