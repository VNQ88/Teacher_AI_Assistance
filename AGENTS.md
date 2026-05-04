# AGENTS Guide - TeacherAssistantAI

## Big Picture
- Backend is a modular Spring Boot 4 monolith (`src/main/java/com/example/teacherassistantai`) with layered flow: `controller -> service -> repository -> entity`.
- Main domains are auth/users, subjects/classrooms, question bank, exams/submissions, and storage integrations.
- API base path is `/api/` from `src/main/resources/application.yml`; controller mappings are relative (e.g. `/exams`, `/question-banks`).
- Standard response wrapper is `ResponseData<T>` (`status`, `message`, `data`) in `common/response/ResponseData.java`.
- Error shape is handled centrally by `exception/GlobalExceptionHandler.java`.

## Runtime + Dependencies
- Java 21 + Gradle Kotlin DSL (`build.gradle.kts`).
- Infra in `compose.yml`: PostgreSQL + pgvector, Redis, MinIO.
- Core integrations: JWT security, Redis token blacklist/OTP storage, MinIO object storage, SMTP email, OpenAPI.
- AI integration uses DigitalOcean Serverless Inference through Spring AI OpenAI-compatible APIs.
- Current AI models: chat `openai-gpt-oss-120b`, embeddings `qwen3-embedding-0.6b` with `vector(1024)` pgvector storage.
- Entry point enables async/scheduling/caching/auditing in `TeacherAssistantAiApplication.java`.

## Critical Workflow Notes
- Start local infra first (from repo root):
  - `docker compose -f compose.yml up -d`
- Run app/tests:
  - `./gradlew bootRun`
  - `./gradlew test`
- OpenAPI UI is enabled via springdoc + `SwaggerConfig`.
- Liquibase is disabled. Current DB schema is current-sql.sql; update this and include in changelog when changing schema.

## Project-Specific Conventions
- Authorization is mostly method-level via `@PreAuthorize`; global security is in `security/SecurityConfig.java`.
- Services enforce business transitions (example: exam lifecycle in `ExamService` + `SubmissionService`).
- Ownership/role checks are commonly done in service layer using `SecurityContextHolder` and role name string checks (`ADMIN`, `TEACHER`, `STUDENT`).
- Paging responses use `PageResponse<?>` wrappers (see `QuestionBankService`, `ExamService`, `UserService`).
- DTO mapping is mostly manual in services (`toResponse(...)` helpers), with selective mapper classes (`mapper/UserMapper.java`).

## Integration and Data Flow Hotspots
- Auth flow: `AuthenticationController` -> `AuthenticationService` -> JWT (`JwtService`) + Redis revocation (`integration/redis/RedisTokenService`).
- Refresh/logout currently read refresh token from `HttpHeaders.REFERER` (non-standard; preserve unless intentionally refactoring).
- Exam flow: create/update while `DRAFT`, publish/cancel in `SubmissionService`, student start/submit/grading in `SubmissionService`.
- Auto-grading is implemented for `MULTIPLE_CHOICE`, `TRUE_FALSE`, `MULTI_SELECT`; text answers remain `PENDING` for later AI/teacher grading.
- MinIO upload/presign logic is centralized in `integration/minio/MinioChannel.java` and `StorageController.java`.
- RAG chat flow: `RagChatController` -> `RagChatService` -> `VectorRetrievalService` + `LocalRerankingService` -> `integration/ai` gateways.
- Reranking is local only. Do not add LLM/dedicated reranker calls unless explicitly requested.

## Guardrails for Agents
- Keep API payload style consistent with `ResponseData` and existing exception classes (`InvalidDataException`, `ResourceNotFoundException`).
- When adding endpoints, mirror existing controller style: validation annotations, concise log lines, service delegation.
- When changing DB schema, update Liquibase changelog includes (not just SQL files).
- When changing embedding model/dimensions, update `application.rag.embedding-dimensions`, `current-sql.sql`, and a migration script; old document embeddings must not be mixed with new embeddings.
- Be careful with startup bootstrap in `ApplicationInitConfig`: it creates default roles/users and currently stores default passwords directly.
- Tests are minimal (`TeacherAssistantAiApplicationTests` only); add focused service/controller tests for behavior changes.
