# DigitalOcean AI Migration Plan

## 1. Scope

Migrate TeacherAssistantAI from Gemini-compatible configuration to DigitalOcean Serverless Inference using OpenAI-compatible APIs.

Confirmed decisions:

- Provider: DigitalOcean Serverless Inference OpenAI-compatible API.
- Replace Gemini completely. No rollback provider switch.
- Secrets are supplied through `.env` and environment variables. No hardcoded API keys in YAML.
- Package naming moves from `integration/gemini` to `integration/ai`.
- Chat model: `openai-gpt-oss-120b`.
- Embedding model: `qwen3-embedding-0.6b`.
- No dedicated reranker model. Reranking is handled in-app only for this migration.
- Future summary model: `openai-gpt-oss-120b`.
- Reindex is acceptable. If needed, old document data can be deleted.
- Old document data should be deleted automatically during the embedding schema migration.
- DB schema should migrate to the new embedding dimension.
- Reindex endpoint/admin command is deferred to a later phase.
- Mail/JWT/MinIO hardcoded dev secrets should be cleaned in the same PR.
- Validation target after implementation: run full `./gradlew test`.

## 2. Current State

Current provider coupling is concentrated in RAG and document ingestion:

- `src/main/java/com/example/teacherassistantai/integration/gemini/GeminiChatGateway.java`
- `src/main/java/com/example/teacherassistantai/integration/gemini/GeminiEmbeddingGateway.java`
- `src/main/java/com/example/teacherassistantai/config/SpringAiRagConfig.java`
- `src/main/java/com/example/teacherassistantai/config/RagProperties.java`
- `src/main/java/com/example/teacherassistantai/service/RagChatService.java`
- `src/main/java/com/example/teacherassistantai/service/VectorRetrievalService.java`
- `src/main/java/com/example/teacherassistantai/service/DocumentChunkIngestionService.java`
- tests importing `GeminiChatGateway` or `GeminiEmbeddingGateway`

Current config uses Spring AI OpenAI starter but points it at Gemini OpenAI-compatible endpoints:

- `spring.ai.openai.api-key`
- `spring.ai.openai.chat.base-url`
- `spring.ai.openai.chat.completions-path`
- `spring.ai.openai.chat.options.model`
- `spring.ai.openai.embedding.base-url`
- `spring.ai.openai.embedding.embeddings-path`
- `spring.ai.openai.embedding.options.model`
- `application.rag.gemini.*`

Current vector schema is Gemini-sized:

- `document_chunks.embedding halfvec(3072)`
- `application.rag.embedding-dimensions: 3072`
- native SQL casts query/document embeddings as `halfvec`

Qwen3 Embedding 0.6B supports 1024-dimensional embeddings, so the target schema should become:

- `document_chunks.embedding vector(1024)`
- `application.rag.embedding-dimensions: 1024`

## 3. External API Assumptions

DigitalOcean Serverless Inference:

- Base URL: `https://inference.do-ai.run`
- Chat endpoint: `/v1/chat/completions`
- Embeddings endpoint: `/v1/embeddings`
- Auth: `Authorization: Bearer ${DIGITALOCEAN_MODEL_ACCESS_KEY}`.
- Embeddings request is OpenAI-compatible and supports `input`, `model`, and `encoding_format`.

Model assumptions:

- Chat model ID: `openai-gpt-oss-120b`.
- Embedding model ID: `qwen3-embedding-0.6b`.
- Embedding dimension: 1024.
- Reranking: local heuristic reranking only. LLM reranking is intentionally deferred.

Important risk:

- DigitalOcean embeddings API examples include `qwen3-embedding-0.6b`, but the implementation should still verify the returned vector length at startup or first call because the project schema depends on exactly 1024 dimensions.
- Retrieval quality depends on the current local reranking heuristics after vector candidate retrieval. Keep this deterministic during the migration, then evaluate improvements separately after DigitalOcean chat/embedding is stable.

References:

- DigitalOcean Serverless Inference endpoints: https://docs.digitalocean.com/products/inference/how-to/si-endpoints/
- DigitalOcean Serverless Inference API reference: https://docs.digitalocean.com/reference/api/reference/serverless-inference/
- DigitalOcean model catalog: https://docs.digitalocean.com/products/ai-platform/details/models/
- Qwen3 Embedding 0.6B model card: https://huggingface.co/Qwen/Qwen3-Embedding-0.6B

## 4. Phase 0 - Preflight And Secret Cleanup

Goal: remove unsafe hardcoded provider secrets and make the app configurable for DigitalOcean.

Tasks:

1. Rotate any existing committed/exposed Gemini/OpenAI/mail secrets outside the codebase.
2. Replace hardcoded provider secrets in `application-dev.yml` with environment placeholders:
   - `${DIGITALOCEAN_MODEL_ACCESS_KEY}`
   - `${DIGITALOCEAN_BASE_URL:https://inference.do-ai.run}`
   - `${DIGITALOCEAN_CHAT_MODEL:openai-gpt-oss-120b}`
   - `${DIGITALOCEAN_EMBEDDING_MODEL:qwen3-embedding-0.6b}`
3. Update `.env` with placeholder names only if the file is tracked/used locally. Do not commit real values.
4. Clean hardcoded mail/JWT/MinIO dev secrets in the same PR by moving them to environment placeholders.
5. Confirm app still starts config binding without provider-specific hardcoded values.

Acceptance criteria:

- No Gemini API key remains in application YAML.
- No OpenAI API key remains in application YAML.
- No hardcoded mail/JWT/MinIO secret remains in application YAML.
- DigitalOcean provider values come from env vars or safe defaults.

## 5. Phase 1 - Rename Provider Layer To `integration/ai`

Goal: remove Gemini naming from production code and make the service layer provider-neutral.

Tasks:

1. Create provider-neutral gateway contracts:
   - `AiChatGateway`
   - `AiEmbeddingGateway`
2. Move implementation package:
   - from `integration/gemini`
   - to `integration/ai`
3. Rename implementations:
   - `GeminiChatGateway` -> `DigitalOceanChatGateway` or `SpringAiChatGateway`
   - `GeminiEmbeddingGateway` -> `DigitalOceanEmbeddingGateway` or `SpringAiEmbeddingGateway`
4. Update service injections:
   - `RagChatService` depends on `AiChatGateway`.
   - `VectorRetrievalService` depends on `AiEmbeddingGateway`.
   - `DocumentChunkIngestionService` depends on `AiEmbeddingGateway`.
5. Update tests to mock provider-neutral interfaces instead of Gemini classes.
6. Remove `application.rag.gemini` from `RagProperties`.
7. Add `application.rag.ai` or `application.rag.digitalocean` properties:
   - `baseUrl`
   - `chatModel`
   - `embeddingModel`
   - `timeoutSeconds`
   - optional `maxOutputTokens`

Acceptance criteria:

- No production import references `integration.gemini`.
- Service layer has no Gemini-specific class names.
- Tests compile against provider-neutral interfaces.

## 6. Phase 2 - DigitalOcean OpenAI-Compatible Chat And Embeddings

Goal: make existing RAG chat and document embedding calls use DigitalOcean Serverless Inference.

Preferred path:

1. Continue using `spring-ai-starter-model-openai`.
2. Configure it for DigitalOcean:
   - `spring.ai.openai.api-key=${DIGITALOCEAN_MODEL_ACCESS_KEY}`
   - `spring.ai.openai.chat.base-url=https://inference.do-ai.run`
   - `spring.ai.openai.chat.completions-path=/v1/chat/completions`
   - `spring.ai.openai.chat.options.model=openai-gpt-oss-120b`
   - `spring.ai.openai.embedding.base-url=https://inference.do-ai.run`
   - `spring.ai.openai.embedding.embeddings-path=/v1/embeddings`
   - `spring.ai.openai.embedding.options.model=qwen3-embedding-0.6b`
3. Keep `ChatClient` config but rename bean from `geminiChatClient` to a neutral name, for example `ragChatClient`.
4. Ensure per-request temperature behavior is either supported or intentionally ignored with a TODO.

Fallback path if Spring AI is incompatible with DigitalOcean response shape:

1. Implement `DigitalOceanChatGateway` with `WebClient`.
2. Implement `DigitalOceanEmbeddingGateway` with `WebClient`.
3. Parse OpenAI-compatible chat response:
   - `choices[0].message.content`
   - `usage.prompt_tokens`
   - `usage.completion_tokens`
   - `usage.total_tokens`
4. Parse OpenAI-compatible embedding response:
   - `data[0].embedding`
   - `usage.total_tokens`
5. Wrap provider failures in `ExternalServiceException`.

Acceptance criteria:

- Chat requests target DigitalOcean, not Gemini.
- Embedding requests target DigitalOcean, not Gemini.
- Empty prompt/input behavior remains deterministic.
- Provider errors map cleanly to existing global exception handling.

## 7. Phase 3 - Embedding Schema Migration To Qwen3 Embedding 0.6B

Goal: migrate vector storage from Gemini 3072-dimensional embeddings to Qwen3 Embedding 0.6B 1024-dimensional embeddings.

Tasks:

1. Update config:
   - `application.rag.embedding-dimensions: 1024`
2. Add DB migration SQL under `src/main/resources/db/changes-in-past/` or the current project migration location:
   - drop `idx_chunk_embedding_hnsw`
   - set existing `document_chunks.embedding = NULL`
   - alter `document_chunks.embedding` to `vector(1024)`
   - recreate HNSW index with `vector_cosine_ops`
   - update column comment to DigitalOcean Qwen3 Embedding 0.6B/1024
3. Update `src/main/resources/db/current-sql.sql`:
   - `embedding vector(1024)`
   - comment reflects DigitalOcean Qwen3 Embedding 0.6B
4. Confirm native SQL casts still work:
   - update `CAST(:queryEmbedding AS halfvec)` to `CAST(:queryEmbedding AS vector)`
   - update `CAST(:embedding AS halfvec)` to `CAST(:embedding AS vector)`
5. Delete old document data automatically in the migration so stale Gemini vectors cannot remain queryable:
   - delete chat message source links for old chunks
   - delete old `document_chunks`
   - delete old document hierarchy nodes/artifacts if present
   - delete old `documents`
   - preserve subjects/classrooms/users/question banks/exams
6. Document that teachers should upload/process documents again after the migration.

Acceptance criteria:

- App rejects non-1024 embeddings through current dimension validation.
- `document_chunks.embedding` schema matches Qwen3 Embedding 0.6B output.
- Existing Gemini vectors are not mixed with Qwen3 vectors.
- Old document/chunk/vector data is removed automatically by the migration.

## 8. Phase 4 - Reindex Strategy

Goal: restore searchable vectors after schema migration.

Phase 4A, immediate implementation:

1. Do not add a new admin reindex endpoint yet.
2. Support reprocessing through existing document upload/process flow.
3. Ensure document processing failure sets document status to `FAILED` with meaningful processing error.
4. Document a manual cleanup/reupload procedure.

Phase 4B, later implementation:

1. Add admin endpoint or service method to reindex one document.
2. Add bulk reindex by subject/classroom.
3. Add resumable progress tracking.
4. Add backoff/rate limit handling for embedding calls.

Acceptance criteria for current migration:

- Freshly processed documents receive Qwen3 embeddings.
- RAG retrieval works only over chunks with non-null 1024-dimensional embeddings.

## 9. Phase 5 - Reranking Strategy

Goal: keep candidate ordering deterministic and local while the provider migration is completed.

Tasks:

1. Keep the current in-app reranking pipeline:
   - vector candidate retrieval through pgvector
   - token overlap scoring
   - section intent boost
   - chunk type boost
   - parent-aware selection
2. Refactor reranking logic into a clearer internal service if it keeps `VectorRetrievalService` from growing too large.
3. Do not add LLM reranking in this migration.
4. Do not add a dedicated reranker gateway in this migration.
5. Add tests around local reranking behavior if refactoring changes method boundaries.

Acceptance criteria:

- Base RAG flow does not depend on a dedicated reranker model.
- Local reranking remains deterministic and testable.
- No request to DigitalOcean is made for reranking.

## 10. Phase 6 - Token Usage And Logging

Goal: improve observability without leaking prompt or document content.

Tasks:

1. If Spring AI exposes provider usage, store real token usage instead of `prompt.length() / 4`.
2. If custom `WebClient` gateway is used, return an AI response object:
   - content
   - promptTokens
   - completionTokens
   - totalTokens
   - model
   - responseTimeMs
3. Update `AgentLog` and `ChatMessage.tokensUsed` from provider usage when available.
4. Log:
   - provider
   - model
   - latency
   - status/success/failure
   - rate-limit headers if accessible
5. Do not log full prompts, full chunks, API keys, or raw document content.

Acceptance criteria:

- Token usage is accurate when provider response includes usage.
- Logs are useful for operations and safe for sensitive educational content.

## 11. Phase 7 - Tests

Goal: protect the migration with focused tests and full project validation.

Tasks:

1. Update existing unit tests importing Gemini gateways.
2. Add or update tests for:
   - `RagProperties` binding defaults.
   - `DocumentChunkIngestionService` dimension validation with 1024-length vectors.
   - `VectorRetrievalService` query embedding validation with 1024-length vectors.
   - `RagChatService` provider-neutral gateway invocation.
3. If using custom `WebClient` gateways, add mock HTTP tests:
   - chat success response
   - embedding success response
   - 401/429/500 error mapping
   - empty/invalid response fallback
4. Run:

```bash
./gradlew test
```

Acceptance criteria:

- Full Gradle test suite passes.
- Tests no longer depend on Gemini-named classes.

## 12. Phase 8 - Documentation And Rollout Notes

Goal: make local setup and migration operation clear.

Tasks:

1. Update `AGENTS.md` if project conventions change around AI provider config.
2. Update RAG docs that still mention Gemini/OpenAI incorrectly:
   - `docs/RAG_CHATBOT_SKELETON.md`
   - `docs/SYSTEM_ARCHITECTURE.md`
   - `docs/entity-relationship-diagram.md`
3. Add `.env` example keys:

```env
DIGITALOCEAN_MODEL_ACCESS_KEY=
DIGITALOCEAN_BASE_URL=https://inference.do-ai.run
DIGITALOCEAN_CHAT_MODEL=openai-gpt-oss-120b
DIGITALOCEAN_EMBEDDING_MODEL=qwen3-embedding-0.6b
```

4. Add manual migration notes:
   - run DB migration
   - confirm old documents were deleted by migration
   - upload/reprocess documents after migration
   - upload a test document
   - verify `/rag/debug` retrieval
   - verify chat answer with citations

Acceptance criteria:

- A developer can configure DigitalOcean locally without reading code.
- Docs no longer describe Gemini as the active AI provider.

## 13. Proposed Implementation Order

Recommended order for the code migration:

1. Phase 0: secret/config cleanup.
2. Phase 1: provider-neutral rename.
3. Phase 2: DigitalOcean chat/embedding wiring.
4. Phase 3: Qwen3/vector(1024) schema migration.
5. Phase 7: update tests and run `./gradlew test`.
6. Phase 8: update docs.
7. Phase 5 and Phase 6 can follow after the base migration is stable.

Do not implement LLM reranking as part of this migration. It touches retrieval quality, latency, and token cost, so it should be evaluated in a later dedicated change.

## 14. Final Confirmed Items

1. Use `DIGITALOCEAN_MODEL_ACCESS_KEY` as the DigitalOcean credential environment variable.
2. `qwen3-embedding-0.6b` is the confirmed embedding model for `/v1/embeddings`.
3. Old documents should be deleted automatically in the schema migration.
4. Mail/JWT/MinIO hardcoded dev secrets should be cleaned in the same PR.
