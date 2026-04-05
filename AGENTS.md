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
- Entry point enables async/scheduling/caching/auditing in `TeacherAssistantAiApplication.java`.

## Critical Workflow Notes
- Start local infra first (from repo root):
  - `docker compose -f compose.yml up -d`
- Run app/tests:
  - `./gradlew bootRun`
  - `./gradlew test`
- OpenAPI UI is enabled via springdoc + `SwaggerConfig`.
- Liquibase is enabled, but `db.changelog-master.yaml` currently includes only `v1.1-add-vector-column.sql`; `v1.0-initial-schema.sql` and seeds are commented out.
- `init-db.sql` also creates pgvector extension for container bootstrap.

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

## Guardrails for Agents
- Keep API payload style consistent with `ResponseData` and existing exception classes (`InvalidDataException`, `ResourceNotFoundException`).
- When adding endpoints, mirror existing controller style: validation annotations, concise log lines, service delegation.
- When changing DB schema, update Liquibase changelog includes (not just SQL files).
- Be careful with startup bootstrap in `ApplicationInitConfig`: it creates default roles/users and currently stores default passwords directly.
- Tests are minimal (`TeacherAssistantAiApplicationTests` only); add focused service/controller tests for behavior changes.
