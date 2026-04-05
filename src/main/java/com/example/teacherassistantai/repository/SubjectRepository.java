package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.entity.Subject;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubjectRepository extends JpaRepository<Subject, Long> {
    boolean existsByNameIgnoreCase(String name);

    boolean existsByCodeIgnoreCase(String code);
}
