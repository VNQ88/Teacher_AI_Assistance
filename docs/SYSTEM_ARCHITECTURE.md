# 🏗️ Kiến trúc hệ thống Multi-Agent Teaching Assistant

## 📊 Tổng quan kiến trúc

```
┌─────────────────────────────────────────────────────────────────────┐
│                          CLIENT LAYER                                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │   Web UI     │  │  Mobile App  │  │  Postman/API │              │
│  │  (React)     │  │   (Future)   │  │    Client    │              │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘              │
│         └──────────────────┴──────────────────┘                      │
│                            │ HTTPS                                    │
└────────────────────────────┼────────────────────────────────────────┘
                             │
┌────────────────────────────┼────────────────────────────────────────┐
│                    API GATEWAY / LOAD BALANCER                       │
│                         (Spring Security)                            │
│                    JWT Authentication & Authorization                │
└────────────────────────────┼────────────────────────────────────────┘
                             │
┌────────────────────────────┼────────────────────────────────────────┐
│                       SPRING BOOT APPLICATION                        │
│                                                                       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │                    CONTROLLER LAYER                         │    │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐     │    │
│  │  │   Chat   │ │ Document │ │ Question │ │   Exam   │     │    │
│  │  │Controller│ │Controller│ │Controller│ │Controller│     │    │
│  │  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘     │    │
│  └───────┼────────────┼────────────┼────────────┼────────────┘    │
│          │            │            │            │                   │
│  ┌───────┼────────────┼────────────┼────────────┼────────────┐    │
│  │                    SERVICE LAYER                            │    │
│  │       │            │            │            │              │    │
│  │  ┌────▼─────┐ ┌───▼──────┐ ┌──▼──────┐ ┌───▼──────┐      │    │
│  │  │   Chat   │ │ Document │ │ Question │ │   Exam   │      │    │
│  │  │ Service  │ │Processing│ │Generator │ │ Service  │      │    │
│  │  └────┬─────┘ │ Service  │ │ Service  │ └──────────┘      │    │
│  │       │       └────┬─────┘ └────┬─────┘                    │    │
│  │       │            │            │                           │    │
│  │  ┌────▼────────────▼────────────▼────────┐                │    │
│  │  │      MULTI-AGENT ORCHESTRATION        │                │    │
│  │  │  ┌──────────────────────────────────┐ │                │    │
│  │  │  │      AgentRouterService          │ │                │    │
│  │  │  │   (Intent Classification)        │ │                │    │
│  │  │  └────────┬─────────────────────────┘ │                │    │
│  │  │           │                            │                │    │
│  │  │  ┌────────▼────────┐  ┌──────────────▼┐               │    │
│  │  │  │  System Agent   │  │ Academic Agent│               │    │
│  │  │  │  ┌───────────┐  │  │ (RAG Service) │               │    │
│  │  │  │  │Query DB   │  │  │  ┌──────────┐ │               │    │
│  │  │  │  │for grades,│  │  │  │ Vector   │ │               │    │
│  │  │  │  │schedule,  │  │  │  │ Search   │ │               │    │
│  │  │  │  │classes    │  │  │  └────┬─────┘ │               │    │
│  │  │  │  └───────────┘  │  │       │       │               │    │
│  │  │  └─────────────────┘  │  ┌────▼─────┐ │               │    │
│  │  │                       │  │GPT-4 Gen │ │               │    │
│  │  │  ┌─────────────────┐ │  └──────────┘ │               │    │
│  │  │  │ Question Gen    │ └───────────────┘               │    │
│  │  │  │ Agent           │                                  │    │
│  │  │  │ ┌─────────────┐ │                                  │    │
│  │  │  │ │Extract chunks│ │                                  │    │
│  │  │  │ │GPT-4 generate│ │                                  │    │
│  │  │  │ │questions     │ │                                  │    │
│  │  │  │ └─────────────┘ │                                  │    │
│  │  │  └─────────────────┘                                  │    │
│  │  └────────────────────────────────────────────────────┘ │    │
│  │                                                           │    │
│  │  ┌───────────────────────────────────────────────────┐  │    │
│  │  │          INTEGRATION SERVICES                      │  │    │
│  │  │  ┌──────────┐ ┌──────────┐ ┌──────────┐          │  │    │
│  │  │  │ OpenAI   │ │  MinIO   │ │  Redis   │          │  │    │
│  │  │  │ Service  │ │ Service  │ │ Service  │          │  │    │
│  │  │  │(Embed+GPT│ │ (Storage)│ │ (Cache)  │          │  │    │
│  │  │  └──────────┘ └──────────┘ └──────────┘          │  │    │
│  │  └───────────────────────────────────────────────────┘  │    │
│  │                                                           │    │
│  └───────────────────────────────────────────────────────────┘    │
│                                                                    │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │                   REPOSITORY LAYER                          │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐     │  │
│  │  │   User   │ │ Document │ │ Question │ │   Exam   │     │  │
│  │  │   Repo   │ │Chunk Repo│ │   Repo   │ │   Repo   │     │  │
│  │  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘     │  │
│  └───────┼────────────┼────────────┼────────────┼────────────┘  │
│          │            │            │            │                │
└──────────┼────────────┼────────────┼────────────┼────────────────┘
           │            │            │            │
┌──────────▼────────────▼────────────▼────────────▼────────────────┐
│                    DATA PERSISTENCE LAYER                         │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              PostgreSQL 16 + pgvector                     │   │
│  │  ┌─────────┐ ┌─────────┐ ┌──────────────┐ ┌─────────┐  │   │
│  │  │  users  │ │subjects │ │document_chunks│ │questions│  │   │
│  │  │  roles  │ │classes  │ │  (VECTOR)     │ │  exams  │  │   │
│  │  └─────────┘ └─────────┘ └──────────────┘ └─────────┘  │   │
│  │                HNSW Index on embeddings                  │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                   │
│  ┌──────────────────┐  ┌──────────────────┐                    │
│  │  Redis (Cache)   │  │  MinIO (Storage) │                    │
│  │  - Chat sessions │  │  - PDF files     │                    │
│  │  - Query cache   │  │  - DOCX files    │                    │
│  └──────────────────┘  └──────────────────┘                    │
└───────────────────────────────────────────────────────────────────┘
           │                       │
┌──────────▼───────────────────────▼────────────────────────────────┐
│                      EXTERNAL SERVICES                             │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │                    OpenAI API                             │    │
│  │  ┌────────────────────┐  ┌─────────────────────────┐    │    │
│  │  │ text-embedding-    │  │    GPT-4 / GPT-3.5      │    │    │
│  │  │    ada-002         │  │  (Chat Completion)      │    │    │
│  │  │  (1536 dims)       │  │                         │    │    │
│  │  └────────────────────┘  └─────────────────────────┘    │    │
│  └──────────────────────────────────────────────────────────┘    │
└───────────────────────────────────────────────────────────────────┘
```

---

## 🔄 Data Flow Diagrams

### 1️⃣ Document Processing Flow

```
┌─────────────┐
│  Teacher    │
│  uploads    │
│  document   │
└──────┬──────┘
       │
       ▼
┌──────────────────┐
│ DocumentController│
│ POST /documents  │
└──────┬───────────┘
       │
       ▼
┌──────────────────────┐
│ 1. Save to MinIO     │
│ 2. Save metadata DB  │
│    status: PENDING   │
└──────┬───────────────┘
       │
       ▼
┌────────────────────────────┐
│ DocumentProcessingService  │
│ @Async                     │
└──────┬─────────────────────┘
       │
       ▼
┌──────────────────┐
│ 1. Download file │
│ 2. Extract text  │ ──► TextExtractorService
│    (PDF/DOCX)    │
└──────┬───────────┘
       │
       ▼
┌──────────────────┐
│ 3. Chunk text    │ ──► TextChunkingService
│    (1000 chars,  │     (overlap: 200)
│     overlap)     │
└──────┬───────────┘
       │
       ▼
┌────────────────────┐
│ 4. Generate        │ ──► OpenAI API
│    embeddings      │     text-embedding-ada-002
│    (batch)         │     Input: List<String>
└──────┬─────────────┘     Output: List<float[1536]>
       │
       ▼
┌────────────────────┐
│ 5. Save chunks     │ ──► PostgreSQL
│    with embeddings │     INSERT document_chunks
│    to database     │     (content, embedding)
└──────┬─────────────┘
       │
       ▼
┌────────────────────┐
│ 6. Update status:  │
│    READY           │
└────────────────────┘
```

---

### 2️⃣ RAG Pipeline Flow (Academic Agent)

```
┌─────────────┐
│  Student    │
│  asks       │
│  question   │
└──────┬──────┘
       │
       ▼
┌───────────────────┐
│  ChatController   │
│  POST /messages   │
└──────┬────────────┘
       │
       ▼
┌───────────────────┐
│ AgentRouterService│
│ Classify intent   │
└──────┬────────────┘
       │
       ├─ "What is my grade?" ──► System Agent ──► Query DB
       │
       └─ "Explain past tense" ──► Academic Agent (RAG)
                                    │
                                    ▼
                             ┌──────────────────┐
                             │ 1. Embed query   │
                             │    via OpenAI    │
                             └────┬─────────────┘
                                  │
                                  ▼
                             ┌──────────────────────┐
                             │ 2. Vector search     │
                             │    SELECT * FROM     │
                             │    document_chunks   │
                             │    WHERE subject_id  │
                             │    ORDER BY          │
                             │    embedding <=>     │
                             │    query_embedding   │
                             │    LIMIT 5           │
                             └────┬─────────────────┘
                                  │
                                  ▼
                             ┌──────────────────────┐
                             │ 3. Build context     │
                             │    from top 5 chunks │
                             └────┬─────────────────┘
                                  │
                                  ▼
                             ┌──────────────────────┐
                             │ 4. GPT-4 generate    │
                             │    answer            │
                             │    Prompt:           │
                             │    - System: "You    │
                             │      are teacher"    │
                             │    - Context: [chunks│
                             │    - Question: [user]│
                             └────┬─────────────────┘
                                  │
                                  ▼
                             ┌──────────────────────┐
                             │ 5. Save message      │
                             │    to chat_messages  │
                             │ 6. Return response   │
                             └──────────────────────┘
```

---

### 3️⃣ Question Generation Flow

```
┌─────────────┐
│  Teacher    │
│  clicks     │
│  "Generate  │
│  Questions" │
└──────┬──────┘
       │
       ▼
┌────────────────────────┐
│ QuestionBankController │
│ POST /generate         │
└──────┬─────────────────┘
       │
       ▼
┌───────────────────────────┐
│ QuestionGeneratorService  │
└──────┬────────────────────┘
       │
       ▼
┌──────────────────────┐
│ 1. Load document     │
│    chunks            │
└──────┬───────────────┘
       │
       ▼
┌──────────────────────┐
│ 2. Sample diverse    │
│    chunks (e.g., 10) │
└──────┬───────────────┘
       │
       ▼
┌──────────────────────┐
│ 3. For each chunk:   │
│    Call GPT-4 with   │
│    prompt:           │
│    "Generate 2 MCQ   │
│     questions from   │
│     this content..."│
└──────┬───────────────┘
       │
       ▼
┌──────────────────────┐
│ 4. Parse JSON        │
│    response:         │
│    {                 │
│      questions: [    │
│        {             │
│          content: "" │
│          options: [] │
│          correct: "" │
│        }             │
│      ]               │
│    }                 │
└──────┬───────────────┘
       │
       ▼
┌──────────────────────┐
│ 5. Validate & save   │
│    to Question +     │
│    AnswerOption      │
│    tables            │
└──────────────────────┘
```

---

### 4️⃣ Exam Taking Flow

```
┌─────────────┐
│  Student    │
│  starts     │
│  exam       │
└──────┬──────┘
       │
       ▼
┌────────────────────────┐
│ ExamController         │
│ POST /exams/{id}/start │
└──────┬─────────────────┘
       │
       ▼
┌──────────────────────┐
│ 1. Create            │
│    ExamSubmission    │
│    (status: STARTED) │
│ 2. Start timer       │
└──────┬───────────────┘
       │
       ▼
┌──────────────────────────┐
│ Student answers          │
│ questions                │
│ (auto-save every 30s)    │
│                          │
│ POST /exams/{id}/answer  │
└──────┬───────────────────┘
       │
       ▼
┌──────────────────────────┐
│ Save StudentAnswer       │
│ (question_id, answer)    │
└──────┬───────────────────┘
       │
       ▼
┌──────────────────────────┐
│ Student submits final    │
│ POST /exams/{id}/submit  │
└──────┬───────────────────┘
       │
       ▼
┌──────────────────────────┐
│ GradingService           │
│ 1. Auto-grade MCQ/TF     │
│    (compare with         │
│     correct_answer)      │
│ 2. Calculate total score │
│ 3. Update submission:    │
│    status = COMPLETED    │
└──────────────────────────┘
```

---

## 🗂️ Database Schema (Key Tables)

### Core Tables

```sql
-- Users & Authentication
users (id, email, password, full_name, avatar, enabled)
roles (id, name)
user_roles (user_id, role_id)

-- Subject & Classroom
subjects (id, name, code, subject_type, description)
classrooms (id, name, code, subject_id, teacher_id, academic_year)
user_classroom (user_id, classroom_id)

-- Documents & RAG
documents (id, title, file_url, file_type, subject_id, status)
document_chunks (
    id, 
    document_id, 
    subject_id,     -- IMPORTANT: for filtering
    chunk_index, 
    content, 
    embedding vector(1536),  -- pgvector
    metadata
)

-- Questions & Exams
question_banks (id, title, subject_id, created_by)
questions (
    id, 
    question_bank_id, 
    content, 
    question_type,
    difficulty_level,
    explanation,
    is_ai_generated,
    source_chunk_id
)
answer_options (id, question_id, content, is_correct, option_order)

exams (
    id, 
    title, 
    subject_id, 
    classroom_id,
    start_time, 
    end_time,
    duration_minutes,
    total_points
)
exam_questions (exam_id, question_id, points)
exam_submissions (
    id, 
    exam_id, 
    student_id, 
    status,
    score,
    started_at,
    submitted_at
)
student_answers (
    id,
    submission_id,
    question_id,
    answer_text,
    selected_option_id,
    is_correct,
    points_earned
)

-- Chat
chat_sessions (id, user_id, subject_id, title)
chat_messages (
    id, 
    session_id, 
    role (USER/ASSISTANT),
    content,
    agent_type (SYSTEM/ACADEMIC),
    metadata
)

-- Agent Logging
agent_logs (
    id,
    session_id,
    agent_type,
    query,
    response,
    execution_time_ms,
    success
)
```

---

## 🔑 Key Technologies & Their Roles

| Technology | Role | Why? |
|------------|------|------|
| **Spring Boot 4.0** | Backend framework | Modern, production-ready Java framework |
| **PostgreSQL 16** | Primary database | Reliable, supports pgvector extension |
| **pgvector** | Vector database | Embedded in PostgreSQL, no separate DB needed |
| **Liquibase** | Database migration | Version control for database schema |
| **Hibernate/JPA** | ORM | Simplify database operations |
| **OpenAI API** | AI services | Best-in-class embeddings & LLM |
| **MinIO** | File storage | S3-compatible object storage |
| **Redis** | Caching | Fast cache for frequent queries |
| **Spring Security** | Authentication | JWT-based auth |
| **Lombok** | Boilerplate reduction | Clean code |
| **Swagger/OpenAPI** | API docs | Auto-generated documentation |

---

## 📈 Performance Considerations

### Vector Search Optimization
- **Index Type**: HNSW (faster than IVFFlat)
- **Index Parameters**: 
  - `m = 16`: Good balance
  - `ef_construction = 64`: Quality construction
- **Query Performance**: < 100ms for 10k chunks

### Caching Strategy
- **Redis Cache**:
  - Frequently asked questions (cache key: query hash)
  - TTL: 1 hour
  - Invalidation: On document update

### Async Processing
- Document processing runs in background
- Thread pool: 2-5 workers
- Non-blocking API response

### Batch Operations
- Embedding generation: Batch up to 100 texts
- Database inserts: Batch insert chunks
- Reduces API calls & latency

---

## 🔒 Security Considerations

### Authentication & Authorization
- JWT tokens with expiration
- Role-based access control (RBAC)
  - ADMIN: Full access
  - TEACHER: Manage classroom, create exams
  - STUDENT: Take exams, chat

### API Security
- Rate limiting (Redis)
- Input validation
- SQL injection prevention (JPA)

### File Upload Security
- File type validation
- Size limit (e.g., 10MB)
- Virus scanning (future)
- Separate storage (MinIO)

### Data Privacy
- Student data encryption
- Access logs
- GDPR compliance considerations

---

## 🎯 Success Criteria

### Functional
- ✅ Upload document → RAG pipeline works
- ✅ Generate questions from documents
- ✅ Complete exam workflow
- ✅ Multi-agent chatbot responds correctly
- ✅ System agent answers system queries
- ✅ Academic agent answers learning questions

### Performance
- ✅ RAG response time < 5s (p95)
- ✅ Vector search < 100ms
- ✅ Document processing < 2 min for 10 pages
- ✅ System handles 100 concurrent users

### Quality
- ✅ Academic agent accuracy > 85%
- ✅ Question quality (teacher approval) > 80%
- ✅ Agent routing accuracy > 90%
- ✅ Test coverage > 70%

---

## 🚀 Deployment Architecture (Future)

```
┌─────────────────────────────────────────────────────────────┐
│                      PRODUCTION                              │
│                                                              │
│  ┌───────────────┐      ┌───────────────┐                  │
│  │   Nginx       │      │ Docker Swarm  │                  │
│  │ Load Balancer │─────▶│   / K8s       │                  │
│  └───────────────┘      └───────┬───────┘                  │
│                                  │                           │
│                   ┌──────────────┼──────────────┐           │
│                   │              │              │           │
│            ┌──────▼────┐  ┌─────▼─────┐ ┌─────▼─────┐    │
│            │ Spring    │  │ Spring    │ │ Spring    │    │
│            │ Boot      │  │ Boot      │ │ Boot      │    │
│            │ Instance1 │  │ Instance2 │ │ Instance3 │    │
│            └───────────┘  └───────────┘ └───────────┘    │
│                   │              │              │           │
│                   └──────────────┼──────────────┘           │
│                                  │                           │
│            ┌─────────────────────┼─────────────────┐        │
│            │                     │                 │        │
│     ┌──────▼──────┐    ┌────────▼──────┐  ┌──────▼─────┐ │
│     │ PostgreSQL  │    │     Redis     │  │   MinIO    │ │
│     │  (Master +  │    │   (Cluster)   │  │(Distributed│ │
│     │  Replicas)  │    │               │  │  Storage)  │ │
│     └─────────────┘    └───────────────┘  └────────────┘ │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

This architecture provides a scalable, maintainable foundation for your Multi-Agent Teaching Assistant system! 🎓✨

