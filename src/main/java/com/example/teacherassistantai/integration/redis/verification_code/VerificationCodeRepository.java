package com.example.teacherassistantai.integration.redis.verification_code;


import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VerificationCodeRepository extends CrudRepository<VerificationCode, String> {

    List<VerificationCode> findAllByUserIdAndPurpose(Long userId, VerificationCodePurpose purpose);

    Optional<VerificationCode> findByPurposeAndCode(VerificationCodePurpose purpose, String code);
}
