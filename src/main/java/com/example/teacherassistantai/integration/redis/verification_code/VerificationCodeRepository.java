package com.example.teacherassistantai.integration.redis.verification_code;


import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VerificationCodeRepository extends CrudRepository<VerificationCode, String> {

    // Tìm kiếm OTP theo mã code
    Optional<VerificationCode> findByCode(String code);
}
