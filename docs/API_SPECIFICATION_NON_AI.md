# API Specification - Non-AI Features

## Tổng quan
Tài liệu này mô tả chi tiết các API cho các chức năng không sử dụng AI:
1. **Question Bank Management**: CRUD QuestionBank, Question, AnswerOption
2. **Exam Workflow**: Tạo đề, publish, học sinh nộp bài
3. **Auto Grading**: Chấm tự động cho MCQ/TRUE_FALSE
4. **System Chatbot**: Rule-based chatbot trả lời điểm/lịch thi

---

## 1. QUESTION BANK MANAGEMENT APIs

### 1.1. QuestionBank APIs

#### 1.1.1. Tạo QuestionBank
- **Endpoint**: `POST /question-banks`
- **Authorization**: TEACHER, ADMIN
- **Request Body**:
```json
{
  "title": "Bộ câu hỏi Tiếng Anh 10 - Unit 1",
  "description": "Câu hỏi trắc nghiệm về từ vựng và ngữ pháp Unit 1",
  "subjectId": 1,
  "sourceDocumentId": null
}
```
- **Response**:
```json
{
  "code": 201,
  "message": "Question bank created",
  "data": {
    "id": 1,
    "title": "Bộ câu hỏi Tiếng Anh 10 - Unit 1",
    "description": "Câu hỏi trắc nghiệm về từ vựng và ngữ pháp Unit 1",
    "subjectId": 1,
    "subjectName": "Tiếng Anh 10",
    "sourceDocumentId": null,
    "createdById": 2,
    "createdByName": "Teacher Name",
    "published": false,
    "questionCount": 0,
    "createdAt": "2026-03-04T10:00:00",
    "updatedAt": "2026-03-04T10:00:00"
  }
}
```

#### 1.1.2. Lấy danh sách QuestionBanks
- **Endpoint**: `GET /question-banks`
- **Authorization**: Authenticated
- **Query Parameters**:
  - `subjectId` (optional): Filter theo môn học
  - `published` (optional): true/false - filter theo trạng thái
  - `pageNo` (default: 0)
  - `pageSize` (default: 20)
- **Response**:
```json
{
  "code": 200,
  "message": "Question banks",
  "data": {
    "pageNo": 0,
    "pageSize": 20,
    "totalPage": 1,
    "items": [
      {
        "id": 1,
        "title": "Bộ câu hỏi Tiếng Anh 10 - Unit 1",
        "description": "...",
        "subjectId": 1,
        "subjectName": "Tiếng Anh 10",
        "createdById": 2,
        "createdByName": "Teacher Name",
        "published": false,
        "questionCount": 15,
        "createdAt": "2026-03-04T10:00:00"
      }
    ]
  }
}
```

#### 1.1.3. Lấy chi tiết QuestionBank
- **Endpoint**: `GET /question-banks/{id}`
- **Authorization**: Authenticated
- **Response**: Giống như response của Create

#### 1.1.4. Cập nhật QuestionBank
- **Endpoint**: `PUT /question-banks/{id}`
- **Authorization**: TEACHER (owner), ADMIN
- **Request Body**:
```json
{
  "title": "Bộ câu hỏi Tiếng Anh 10 - Unit 1 (Updated)",
  "description": "Updated description",
  "published": true
}
```
- **Response**: Giống như response của Create

#### 1.1.5. Xóa QuestionBank
- **Endpoint**: `DELETE /question-banks/{id}`
- **Authorization**: TEACHER (owner), ADMIN
- **Response**:
```json
{
  "code": 200,
  "message": "Question bank deleted"
}
```

---

### 1.2. Question APIs

#### 1.2.1. Tạo Question
- **Endpoint**: `POST /question-banks/{questionBankId}/questions`
- **Authorization**: TEACHER (owner), ADMIN
- **Request Body**:
```json
{
  "content": "What is the past tense of 'go'?",
  "questionType": "MULTIPLE_CHOICE",
  "difficultyLevel": "EASY",
  "explanation": "The past tense of 'go' is 'went'.",
  "tags": "[\"grammar\", \"irregular-verbs\"]",
  "answerOptions": [
    {
      "content": "goed",
      "isCorrect": false,
      "orderIndex": 0
    },
    {
      "content": "went",
      "isCorrect": true,
      "orderIndex": 1
    },
    {
      "content": "gone",
      "isCorrect": false,
      "orderIndex": 2
    },
    {
      "content": "going",
      "isCorrect": false,
      "orderIndex": 3
    }
  ]
}
```
- **Response**:
```json
{
  "code": 201,
  "message": "Question created",
  "data": {
    "id": 1,
    "questionBankId": 1,
    "content": "What is the past tense of 'go'?",
    "questionType": "MULTIPLE_CHOICE",
    "difficultyLevel": "EASY",
    "explanation": "The past tense of 'go' is 'went'.",
    "tags": "[\"grammar\", \"irregular-verbs\"]",
    "isAiGenerated": false,
    "sourceChunkId": null,
    "answerOptions": [
      {
        "id": 1,
        "content": "goed",
        "isCorrect": false,
        "orderIndex": 0
      },
      {
        "id": 2,
        "content": "went",
        "isCorrect": true,
        "orderIndex": 1
      },
      {
        "id": 3,
        "content": "gone",
        "isCorrect": false,
        "orderIndex": 2
      },
      {
        "id": 4,
        "content": "going",
        "isCorrect": false,
        "orderIndex": 3
      }
    ],
    "createdAt": "2026-03-04T10:00:00"
  }
}
```

#### 1.2.2. Lấy danh sách Questions trong QuestionBank
- **Endpoint**: `GET /question-banks/{questionBankId}/questions`
- **Authorization**: Authenticated
- **Query Parameters**:
  - `questionType` (optional): Filter theo loại câu hỏi
  - `difficultyLevel` (optional): EASY, MEDIUM, HARD
  - `pageNo` (default: 0)
  - `pageSize` (default: 20)
- **Response**:
```json
{
  "code": 200,
  "message": "Questions",
  "data": {
    "pageNo": 0,
    "pageSize": 20,
    "totalPage": 1,
    "items": [
      {
        "id": 1,
        "content": "What is the past tense of 'go'?",
        "questionType": "MULTIPLE_CHOICE",
        "difficultyLevel": "EASY",
        "answerOptionsCount": 4,
        "createdAt": "2026-03-04T10:00:00"
      }
    ]
  }
}
```

#### 1.2.3. Lấy chi tiết Question
- **Endpoint**: `GET /questions/{id}`
- **Authorization**: Authenticated
- **Response**: Giống như response của Create Question

#### 1.2.4. Cập nhật Question
- **Endpoint**: `PUT /questions/{id}`
- **Authorization**: TEACHER (owner), ADMIN
- **Request Body**: Giống như Create Question (trừ answerOptions)
- **Response**: Giống như Create Question

#### 1.2.5. Xóa Question
- **Endpoint**: `DELETE /questions/{id}`
- **Authorization**: TEACHER (owner), ADMIN
- **Response**:
```json
{
  "code": 200,
  "message": "Question deleted"
}
```

---

### 1.3. AnswerOption APIs

#### 1.3.1. Tạo AnswerOption
- **Endpoint**: `POST /questions/{questionId}/answer-options`
- **Authorization**: TEACHER (owner), ADMIN
- **Request Body**:
```json
{
  "content": "New option",
  "isCorrect": false,
  "orderIndex": 4
}
```
- **Response**:
```json
{
  "code": 201,
  "message": "Answer option created",
  "data": {
    "id": 5,
    "questionId": 1,
    "content": "New option",
    "isCorrect": false,
    "orderIndex": 4
  }
}
```

#### 1.3.2. Cập nhật AnswerOption
- **Endpoint**: `PUT /answer-options/{id}`
- **Authorization**: TEACHER (owner), ADMIN
- **Request Body**:
```json
{
  "content": "Updated option",
  "isCorrect": true,
  "orderIndex": 4
}
```
- **Response**: Giống như Create

#### 1.3.3. Xóa AnswerOption
- **Endpoint**: `DELETE /answer-options/{id}`
- **Authorization**: TEACHER (owner), ADMIN
- **Response**:
```json
{
  "code": 200,
  "message": "Answer option deleted"
}
```

---

## 2. EXAM WORKFLOW APIs

### 2.1. Exam Management APIs

#### 2.1.1. Tạo Exam (DRAFT)
- **Endpoint**: `POST /classrooms/{classroomId}/exams`
- **Authorization**: TEACHER (của classroom), ADMIN
- **Request Body**:
```json
{
  "title": "Kiểm tra 15 phút - Unit 1",
  "description": "Kiểm tra từ vựng và ngữ pháp Unit 1",
  "startTime": "2026-03-10T08:00:00",
  "endTime": "2026-03-10T09:00:00",
  "durationMinutes": 15,
  "totalScore": 10,
  "passingScore": 5,
  "shuffleQuestions": true,
  "shuffleOptions": true
}
```
- **Response**:
```json
{
  "code": 201,
  "message": "Exam created",
  "data": {
    "id": 1,
    "title": "Kiểm tra 15 phút - Unit 1",
    "description": "Kiểm tra từ vựng và ngữ pháp Unit 1",
    "classroomId": 1,
    "classroomName": "Tiếng Anh 10A1",
    "createdById": 2,
    "createdByName": "Teacher Name",
    "startTime": "2026-03-10T08:00:00",
    "endTime": "2026-03-10T09:00:00",
    "durationMinutes": 15,
    "totalScore": 10,
    "passingScore": 5,
    "shuffleQuestions": true,
    "shuffleOptions": true,
    "status": "DRAFT",
    "questionCount": 0,
    "submissionCount": 0,
    "createdAt": "2026-03-04T10:00:00"
  }
}
```

#### 2.1.2. Thêm câu hỏi vào Exam
- **Endpoint**: `POST /exams/{examId}/questions`
- **Authorization**: TEACHER (owner), ADMIN
- **Request Body**:
```json
{
  "questionId": 1,
  "orderIndex": 1,
  "score": 1.0
}
```
- **Response**:
```json
{
  "code": 201,
  "message": "Question added to exam",
  "data": {
    "id": 1,
    "examId": 1,
    "questionId": 1,
    "orderIndex": 1,
    "score": 1.0,
    "question": {
      "id": 1,
      "content": "What is the past tense of 'go'?",
      "questionType": "MULTIPLE_CHOICE",
      "difficultyLevel": "EASY"
    }
  }
}
```

#### 2.1.3. Thêm nhiều câu hỏi vào Exam (Bulk)
- **Endpoint**: `POST /exams/{examId}/questions/bulk`
- **Authorization**: TEACHER (owner), ADMIN
- **Request Body**:
```json
{
  "questions": [
    {
      "questionId": 1,
      "orderIndex": 1,
      "score": 1.0
    },
    {
      "questionId": 2,
      "orderIndex": 2,
      "score": 1.0
    },
    {
      "questionId": 3,
      "orderIndex": 3,
      "score": 1.0
    }
  ]
}
```
- **Response**:
```json
{
  "code": 201,
  "message": "3 questions added to exam",
  "data": {
    "addedCount": 3,
    "examQuestions": [...]
  }
}
```

#### 2.1.4. Xóa câu hỏi khỏi Exam
- **Endpoint**: `DELETE /exams/{examId}/questions/{examQuestionId}`
- **Authorization**: TEACHER (owner), ADMIN
- **Response**:
```json
{
  "code": 200,
  "message": "Question removed from exam"
}
```

#### 2.1.5. Lấy danh sách câu hỏi trong Exam
- **Endpoint**: `GET /exams/{examId}/questions`
- **Authorization**: TEACHER (owner), ADMIN, STUDENT (enrolled) - nhưng students chỉ thấy khi exam đã SCHEDULED/ONGOING
- **Response**:
```json
{
  "code": 200,
  "message": "Exam questions",
  "data": [
    {
      "id": 1,
      "examId": 1,
      "questionId": 1,
      "orderIndex": 1,
      "score": 1.0,
      "question": {
        "id": 1,
        "content": "What is the past tense of 'go'?",
        "questionType": "MULTIPLE_CHOICE",
        "answerOptions": [
          {
            "id": 1,
            "content": "goed",
            "orderIndex": 0
          },
          {
            "id": 2,
            "content": "went",
            "orderIndex": 1
          }
        ]
      }
    }
  ]
}
```

#### 2.1.6. Cập nhật Exam
- **Endpoint**: `PUT /exams/{examId}`
- **Authorization**: TEACHER (owner), ADMIN
- **Request Body**: Giống như Create Exam
- **Response**: Giống như Create Exam

#### 2.1.7. Publish Exam (Chuyển từ DRAFT sang SCHEDULED)
- **Endpoint**: `POST /exams/{examId}/publish`
- **Authorization**: TEACHER (owner), ADMIN
- **Validation**: 
  - Exam phải có ít nhất 1 câu hỏi
  - Tổng điểm các câu hỏi phải = totalScore
- **Response**:
```json
{
  "code": 200,
  "message": "Exam published successfully",
  "data": {
    "id": 1,
    "status": "SCHEDULED",
    "questionCount": 10,
    "totalScore": 10
  }
}
```

#### 2.1.8. Cancel Exam
- **Endpoint**: `POST /exams/{examId}/cancel`
- **Authorization**: TEACHER (owner), ADMIN
- **Response**:
```json
{
  "code": 200,
  "message": "Exam cancelled"
}
```

#### 2.1.9. Lấy danh sách Exams
- **Endpoint**: `GET /classrooms/{classroomId}/exams`
- **Authorization**: Authenticated (TEACHER hoặc STUDENT enrolled)
- **Query Parameters**:
  - `status` (optional): DRAFT, SCHEDULED, ONGOING, FINISHED, CANCELLED
- **Response**:
```json
{
  "code": 200,
  "message": "Exams",
  "data": [
    {
      "id": 1,
      "title": "Kiểm tra 15 phút - Unit 1",
      "startTime": "2026-03-10T08:00:00",
      "endTime": "2026-03-10T09:00:00",
      "durationMinutes": 15,
      "status": "SCHEDULED",
      "questionCount": 10,
      "submissionCount": 0
    }
  ]
}
```

#### 2.1.10. Lấy chi tiết Exam
- **Endpoint**: `GET /exams/{examId}`
- **Authorization**: Authenticated
- **Response**: Giống như Create Exam

#### 2.1.11. Xóa Exam
- **Endpoint**: `DELETE /exams/{examId}`
- **Authorization**: TEACHER (owner), ADMIN
- **Validation**: Chỉ xóa được exam có status = DRAFT
- **Response**:
```json
{
  "code": 200,
  "message": "Exam deleted"
}
```

---

### 2.2. Exam Submission APIs (Student)

#### 2.2.1. Bắt đầu làm bài (Start Exam)
- **Endpoint**: `POST /exams/{examId}/start`
- **Authorization**: STUDENT (enrolled in classroom)
- **Validation**:
  - Exam phải có status = SCHEDULED hoặc ONGOING
  - Current time phải trong khoảng startTime - endTime
  - Student chưa có submission cho exam này
- **Response**:
```json
{
  "code": 201,
  "message": "Exam started",
  "data": {
    "submissionId": 1,
    "examId": 1,
    "studentId": 3,
    "startedAt": "2026-03-10T08:05:00",
    "status": "IN_PROGRESS",
    "remainingMinutes": 15,
    "questions": [
      {
        "examQuestionId": 1,
        "orderIndex": 1,
        "score": 1.0,
        "question": {
          "id": 1,
          "content": "What is the past tense of 'go'?",
          "questionType": "MULTIPLE_CHOICE",
          "answerOptions": [
            {
              "id": 1,
              "content": "goed"
            },
            {
              "id": 2,
              "content": "went"
            }
          ]
        }
      }
    ]
  }
}
```

#### 2.2.2. Lưu câu trả lời (Save Answer)
- **Endpoint**: `POST /submissions/{submissionId}/answers`
- **Authorization**: STUDENT (owner)
- **Request Body** (cho MULTIPLE_CHOICE/TRUE_FALSE):
```json
{
  "examQuestionId": 1,
  "selectedOptionId": 2
}
```
- **Request Body** (cho SHORT_ANSWER/ESSAY):
```json
{
  "examQuestionId": 2,
  "answerContent": "The past tense of 'go' is 'went'."
}
```
- **Response**:
```json
{
  "code": 200,
  "message": "Answer saved",
  "data": {
    "studentAnswerId": 1,
    "examQuestionId": 1,
    "selectedOptionId": 2,
    "answerContent": null,
    "gradingStatus": "PENDING"
  }
}
```

#### 2.2.3. Nộp bài (Submit Exam)
- **Endpoint**: `POST /submissions/{submissionId}/submit`
- **Authorization**: STUDENT (owner)
- **Validation**:
  - Submission phải có status = IN_PROGRESS
  - Không quá thời gian làm bài
- **Response**:
```json
{
  "code": 200,
  "message": "Exam submitted successfully",
  "data": {
    "submissionId": 1,
    "submittedAt": "2026-03-10T08:18:00",
    "status": "SUBMITTED",
    "totalScore": 0,
    "gradingStatus": "Đang chấm..."
  }
}
```
- **Note**: Sau khi submit, hệ thống sẽ tự động chấm điểm cho các câu MCQ/TRUE_FALSE

#### 2.2.4. Lấy thông tin submission của student
- **Endpoint**: `GET /exams/{examId}/my-submission`
- **Authorization**: STUDENT (enrolled)
- **Response**:
```json
{
  "code": 200,
  "message": "Submission details",
  "data": {
    "submissionId": 1,
    "examId": 1,
    "examTitle": "Kiểm tra 15 phút - Unit 1",
    "studentId": 3,
    "startedAt": "2026-03-10T08:05:00",
    "submittedAt": "2026-03-10T08:18:00",
    "totalScore": 8.5,
    "status": "AI_GRADED",
    "answers": [
      {
        "studentAnswerId": 1,
        "questionContent": "What is the past tense of 'go'?",
        "questionType": "MULTIPLE_CHOICE",
        "selectedOptionContent": "went",
        "score": 1.0,
        "maxScore": 1.0,
        "gradingStatus": "AUTO_GRADED",
        "isCorrect": true
      }
    ]
  }
}
```

#### 2.2.5. Lấy danh sách submissions của student
- **Endpoint**: `GET /students/my-submissions`
- **Authorization**: STUDENT
- **Query Parameters**:
  - `classroomId` (optional)
  - `status` (optional)
- **Response**:
```json
{
  "code": 200,
  "message": "My submissions",
  "data": [
    {
      "submissionId": 1,
      "examId": 1,
      "examTitle": "Kiểm tra 15 phút - Unit 1",
      "classroomName": "Tiếng Anh 10A1",
      "submittedAt": "2026-03-10T08:18:00",
      "totalScore": 8.5,
      "maxScore": 10,
      "status": "AI_GRADED"
    }
  ]
}
```

---

### 2.3. Exam Grading APIs (Teacher)

#### 2.3.1. Lấy danh sách submissions của Exam
- **Endpoint**: `GET /exams/{examId}/submissions`
- **Authorization**: TEACHER (owner), ADMIN
- **Query Parameters**:
  - `status` (optional)
- **Response**:
```json
{
  "code": 200,
  "message": "Exam submissions",
  "data": [
    {
      "submissionId": 1,
      "studentId": 3,
      "studentName": "Nguyen Van A",
      "studentEmail": "student@example.com",
      "startedAt": "2026-03-10T08:05:00",
      "submittedAt": "2026-03-10T08:18:00",
      "totalScore": 8.5,
      "maxScore": 10,
      "status": "AI_GRADED"
    }
  ]
}
```

#### 2.3.2. Xem chi tiết submission
- **Endpoint**: `GET /submissions/{submissionId}`
- **Authorization**: TEACHER (owner), ADMIN, STUDENT (owner)
- **Response**: Giống như GET my-submission

#### 2.3.3. Chấm lại thủ công (Override score)
- **Endpoint**: `PUT /submissions/{submissionId}/answers/{studentAnswerId}/grade`
- **Authorization**: TEACHER (owner), ADMIN
- **Request Body**:
```json
{
  "score": 0.5,
  "teacherFeedback": "Câu trả lời đúng nhưng chưa đầy đủ"
}
```
- **Response**:
```json
{
  "code": 200,
  "message": "Answer graded",
  "data": {
    "studentAnswerId": 1,
    "score": 0.5,
    "maxScore": 1.0,
    "gradingStatus": "TEACHER_GRADED",
    "teacherFeedback": "Câu trả lời đúng nhưng chưa đầy đủ"
  }
}
```

#### 2.3.4. Finalize submission (Teacher review)
- **Endpoint**: `POST /submissions/{submissionId}/finalize`
- **Authorization**: TEACHER (owner), ADMIN
- **Request Body**:
```json
{
  "teacherComment": "Làm tốt! Cần chú ý hơn về ngữ pháp."
}
```
- **Response**:
```json
{
  "code": 200,
  "message": "Submission finalized",
  "data": {
    "submissionId": 1,
    "status": "TEACHER_REVIEWED",
    "totalScore": 8.5,
    "gradedById": 2,
    "gradedByName": "Teacher Name",
    "gradedAt": "2026-03-10T09:00:00",
    "teacherComment": "Làm tốt! Cần chú ý hơn về ngữ pháp."
  }
}
```

---

## 3. AUTO GRADING (Non-AI)

### Mô tả
Sau khi student submit bài, hệ thống sẽ tự động chấm điểm cho các câu hỏi loại:
- **MULTIPLE_CHOICE**: So sánh selectedOptionId với option có isCorrect = true
- **TRUE_FALSE**: So sánh selectedOptionId với option có isCorrect = true

### Logic chấm điểm:
1. Duyệt qua tất cả StudentAnswer của submission
2. Với mỗi answer có questionType = MULTIPLE_CHOICE hoặc TRUE_FALSE:
   - Lấy Question và AnswerOptions
   - Kiểm tra selectedOption.isCorrect
   - Nếu đúng: score = examQuestion.score
   - Nếu sai: score = 0
   - Update gradingStatus = AUTO_GRADED
3. Tính totalScore = sum(all studentAnswer.score)
4. Update submission status = AI_GRADED (hoặc SUBMITTED nếu có câu essay chưa chấm)

### API trigger auto-grading:
- Tự động chạy sau khi student submit (endpoint: `POST /submissions/{submissionId}/submit`)
- Hoặc teacher có thể trigger lại: `POST /submissions/{submissionId}/auto-grade`

---

## 4. SYSTEM CHATBOT (Rule-based)

### 4.1. Chat Session APIs

#### 4.1.1. Tạo chat session
- **Endpoint**: `POST /chat/sessions`
- **Authorization**: Authenticated
- **Request Body**:
```json
{
  "type": "SYSTEM_SUPPORT"
}
```
- **Response**:
```json
{
  "code": 201,
  "message": "Chat session created",
  "data": {
    "sessionId": 1,
    "userId": 3,
    "type": "SYSTEM_SUPPORT",
    "createdAt": "2026-03-04T10:00:00"
  }
}
```

#### 4.1.2. Gửi message (Rule-based chatbot)
- **Endpoint**: `POST /chat/sessions/{sessionId}/messages`
- **Authorization**: Authenticated (owner)
- **Request Body**:
```json
{
  "message": "Điểm thi của tôi là bao nhiêu?"
}
```
- **Response**:
```json
{
  "code": 200,
  "message": "Message sent",
  "data": {
    "userMessage": {
      "id": 1,
      "role": "USER",
      "content": "Điểm thi của tôi là bao nhiêu?",
      "createdAt": "2026-03-04T10:00:00"
    },
    "botResponse": {
      "id": 2,
      "role": "ASSISTANT",
      "content": "Bạn có 2 bài thi:\n- Kiểm tra 15 phút Unit 1: 8.5/10\n- Kiểm tra 15 phút Unit 2: 9.0/10",
      "createdAt": "2026-03-04T10:00:01"
    }
  }
}
```

#### 4.1.3. Lấy lịch sử chat
- **Endpoint**: `GET /chat/sessions/{sessionId}/messages`
- **Authorization**: Authenticated (owner)
- **Response**:
```json
{
  "code": 200,
  "message": "Chat messages",
  "data": [
    {
      "id": 1,
      "role": "USER",
      "content": "Điểm thi của tôi là bao nhiêu?",
      "createdAt": "2026-03-04T10:00:00"
    },
    {
      "id": 2,
      "role": "ASSISTANT",
      "content": "Bạn có 2 bài thi:\n- Kiểm tra 15 phút Unit 1: 8.5/10",
      "createdAt": "2026-03-04T10:00:01"
    }
  ]
}
```

---

### 4.2. Rule-based Chatbot Logic

Chatbot sẽ nhận diện intent từ message và trả lời bằng cách query DB:

#### 4.2.1. Intent: Xem điểm thi
**Keywords**: điểm, score, kết quả, result
**Query**:
```sql
SELECT e.title, es.total_score, e.total_score as max_score, es.submitted_at
FROM exam_submissions es
JOIN exams e ON es.exam_id = e.id
WHERE es.student_id = {current_user_id}
ORDER BY es.submitted_at DESC
```
**Response template**:
```
Bạn có {count} bài thi:
- {exam_title}: {score}/{max_score} (ngày {date})
- ...
```

#### 4.2.2. Intent: Xem lịch thi
**Keywords**: lịch thi, exam schedule, khi nào thi, when exam
**Query**:
```sql
SELECT e.title, e.start_time, e.end_time, c.name as classroom_name
FROM exams e
JOIN classrooms c ON e.classroom_id = c.id
JOIN user_classroom uc ON c.id = uc.classroom_id
WHERE uc.user_id = {current_user_id}
  AND e.status IN ('SCHEDULED', 'ONGOING')
  AND e.start_time > NOW()
ORDER BY e.start_time ASC
```
**Response template**:
```
Lịch thi sắp tới:
- {exam_title} - Lớp {classroom_name}
  Thời gian: {start_time} - {end_time}
- ...
```

#### 4.2.3. Intent: Xem lớp học
**Keywords**: lớp học, classroom, class, môn học
**Query**:
```sql
SELECT c.name, c.code, s.name as subject_name, u.full_name as teacher_name
FROM classrooms c
JOIN user_classroom uc ON c.id = uc.classroom_id
JOIN subjects s ON c.subject_id = s.id
JOIN users u ON c.teacher_id = u.id
WHERE uc.user_id = {current_user_id}
```
**Response template**:
```
Bạn đang học {count} lớp:
- {classroom_name} ({code})
  Môn: {subject_name}
  Giáo viên: {teacher_name}
- ...
```

#### 4.2.4. Intent: Unknown (Fallback)
**Response**:
```
Xin lỗi, tôi chưa hiểu câu hỏi của bạn. Bạn có thể hỏi về:
- Điểm thi của bạn
- Lịch thi sắp tới
- Các lớp học bạn đang tham gia
```

---

## 5. Validation Rules

### QuestionBank
- title: required, max 255 chars
- subjectId: required, must exist
- published: boolean

### Question
- content: required
- questionType: required, valid enum
- answerOptions: 
  - MULTIPLE_CHOICE: 2-10 options, exactly 1 isCorrect=true
  - TRUE_FALSE: exactly 2 options (True/False), 1 isCorrect=true
  - SHORT_ANSWER/ESSAY: 0-1 options (answer key)

### Exam
- title: required
- startTime < endTime
- durationMinutes > 0
- totalScore > 0
- passingScore <= totalScore
- Khi publish: phải có ít nhất 1 câu hỏi

### Submission
- Student chỉ có thể submit 1 lần cho mỗi exam
- Chỉ submit được trong thời gian: exam.startTime <= now <= exam.endTime
- Không submit được sau khi đã submit

---

## 6. Error Codes

| Code | Message | Description |
|------|---------|-------------|
| 400 | Invalid data | Dữ liệu đầu vào không hợp lệ |
| 401 | Unauthorized | Chưa đăng nhập |
| 403 | Forbidden | Không có quyền truy cập |
| 404 | Resource not found | Không tìm thấy resource |
| 409 | Conflict | Trùng lặp dữ liệu (ví dụ: student đã submit) |
| 422 | Validation failed | Validate business logic thất bại |
| 500 | Internal server error | Lỗi server |

---

## 7. Thứ tự triển khai đề xuất

### Phase 1: Question Bank (3-4 days)
1. QuestionBank CRUD APIs
2. Question CRUD APIs
3. AnswerOption CRUD APIs
4. Test với Swagger/Postman

### Phase 2: Exam Management (4-5 days)
1. Exam CRUD APIs
2. Add/Remove questions to Exam
3. Publish/Cancel Exam
4. Test workflow

### Phase 3: Exam Submission (3-4 days)
1. Start exam
2. Save answers
3. Submit exam
4. View submission

### Phase 4: Auto Grading (2-3 days)
1. Auto-grade MCQ/TRUE_FALSE
2. Teacher manual grading
3. Finalize submission

### Phase 5: System Chatbot (2-3 days)
1. Chat session management
2. Rule-based intent detection
3. Query DB và response generation

**Total: ~14-19 days**

