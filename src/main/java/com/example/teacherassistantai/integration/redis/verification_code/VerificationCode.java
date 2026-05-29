package com.example.teacherassistantai.integration.redis.verification_code;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;
import org.springframework.data.redis.core.index.Indexed;
import lombok.*;

import java.util.concurrent.TimeUnit;

@RedisHash("VerificationCode")
@Builder
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class VerificationCode {

    @Id
    private String id; // Spring Data Redis ưu tiên dùng String cho @Id

    // Đánh dấu @Indexed để Spring Data Redis có thể tìm kiếm theo field này
    @Indexed
    private String code;

    // Lưu userId thay vì cả object User để giảm dung lượng cache
    @Indexed
    private Long userId;

    @Indexed
    private VerificationCodePurpose purpose;

    // Redis sẽ tự động đếm ngược và xóa record này
    @TimeToLive(unit = TimeUnit.SECONDS)
    private Long timeToLive;
}
