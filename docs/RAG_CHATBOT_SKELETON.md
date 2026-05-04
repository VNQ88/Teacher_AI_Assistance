# RAG Chatbot Skeleton (Spring AI + DigitalOcean)

## What Is Included

- Subject-scoped chat session APIs:
  - `POST /chat/sessions`
  - `GET /chat/sessions`
  - `GET /chat/sessions/{sessionId}`
  - `PATCH /chat/sessions/{sessionId}/close`
  - `DELETE /chat/sessions/{sessionId}`
- RAG message APIs:
  - `POST /chat/sessions/{sessionId}/messages`
  - `GET /chat/sessions/{sessionId}/messages`
- Vector storage:
  - `DocumentChunk` does not map the embedding field directly.
  - Repository uses native SQL with vector literal casts.
  - Current schema stores embeddings as `vector(1024)`.
- AI provider:
  - DigitalOcean Serverless Inference via Spring AI OpenAI-compatible APIs.
  - Chat model: `openai-gpt-oss-120b`.
  - Embedding model: `qwen3-embedding-0.6b`.
- Ingestion pipeline:
  - Tika markdown parsing.
  - Hierarchical markdown chunking.
  - Chunk metadata builder.
  - Qwen3 embedding generation.
  - Persist chunks and update embeddings via native query.
- Retrieval pipeline:
  - pgvector candidate search.
  - Local deterministic reranking through `LocalRerankingService`.
  - Parent-aware chunk selection.

## Required Migration

Liquibase is disabled. Apply migration scripts manually when changing schema.

Current DigitalOcean migration:

- `src/main/resources/db/changes-in-past/v2.0-migrate-to-digitalocean-qwen3-vector-1024.sql`

This migration deletes old document-derived data, changes `document_chunks.embedding` to `vector(1024)`, and recreates the HNSW cosine index.

## Notes

- Provider integration classes live under `src/main/java/com/example/teacherassistantai/integration/ai`.
- Service code should depend on `AiChatGateway` and `AiEmbeddingGateway`, not provider implementation classes.
- Reranking is local only. Do not add LLM reranking or a dedicated reranker model unless explicitly requested.
- After schema migration, teachers need to upload/process documents again so Qwen3 embeddings are generated.
