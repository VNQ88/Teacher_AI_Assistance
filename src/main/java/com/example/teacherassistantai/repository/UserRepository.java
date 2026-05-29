package com.example.teacherassistantai.repository;

import com.example.teacherassistantai.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    Boolean existsByEmail(String email);

    @Modifying
    @Query(value = "DELETE FROM users_roles WHERE users_id = :userId", nativeQuery = true)
    void deleteRoleLinksByUserId(@Param("userId") Long userId);
}
