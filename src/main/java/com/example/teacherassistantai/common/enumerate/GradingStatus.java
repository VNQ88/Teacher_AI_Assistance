package com.example.teacherassistantai.common.enumerate;

public enum GradingStatus {
    PENDING,          // Chưa chấm (thường là essay/short-answer)
    AUTO_GRADED,      // Tự động chấm (MCQ, true/false)
    AI_GRADED,        // AI chấm (short-answer, essay)
    TEACHER_REVIEWED  // Giáo viên đã xem lại và xác nhận
}
