# DigitalOcean Migration Runbook

## Scope

This runbook covers the current Gemini-to-DigitalOcean migration state:

- Chat uses DigitalOcean Serverless Inference through OpenAI-compatible chat completions.
- Embeddings use `qwen3-embedding-0.6b`.
- `document_chunks.embedding` is `vector(1024)`.
- Existing document-derived data is deleted during migration.
- No admin reindex endpoint exists yet.
- Reranking is local only. No LLM or dedicated reranker model is called.

## Required Environment

Set these values before running the app:

```env
DIGITALOCEAN_MODEL_ACCESS_KEY=
DIGITALOCEAN_BASE_URL=https://inference.do-ai.run
DIGITALOCEAN_CHAT_MODEL=openai-gpt-oss-120b
DIGITALOCEAN_EMBEDDING_MODEL=qwen3-embedding-0.6b
JWT_SECRET_KEY=
APP_ENCRYPTION_KEY=
MAIL_USERNAME=
MAIL_PASSWORD=
MINIO_ROOT_USER=
MINIO_ROOT_PASSWORD=
```

`DIGITALOCEAN_MODEL_ACCESS_KEY` must be a real key outside tests/dev context. The placeholder `not-configured` only prevents Spring AI auto-configuration from failing during local context startup.

## Database Migration

Apply:

```bash
src/main/resources/db/changes-in-past/v2.0-migrate-to-digitalocean-qwen3-vector-1024.sql
```

The migration:

1. Deletes `message_source_chunks` rows linked to old document chunks.
2. Clears `question_banks.source_document_id`.
3. Deletes old `document_chunks`.
4. Deletes old `document_nodes`.
5. Deletes old `documents`.
6. Changes `document_chunks.embedding` to `vector(1024)`.
7. Recreates the HNSW cosine index with `vector_cosine_ops`.

It preserves users, subjects, classrooms, question banks, exams, submissions, and chat history.

## Reupload Flow

After migration, teachers should upload documents again through the existing document APIs. The normal processing pipeline will:

1. Parse the uploaded source file.
2. Build hierarchy artifacts.
3. Persist document nodes.
4. Chunk document content.
5. Generate Qwen3 1024-dimensional embeddings.
6. Store embeddings in pgvector.
7. Mark the document `READY`.

If processing fails, `DocumentProcessingService` marks the document `FAILED` and stores `processingError`.

## Verification

1. Upload a small document.
2. Wait for status `READY`.
3. Use RAG debug retrieval to confirm selected chunks are returned.
4. Send a chat message and confirm the answer includes source details.
5. Run:

```bash
./gradlew test
```

## Deferred Work

- Add admin reindex endpoint for one document.
- Add bulk reindex by subject/classroom.
- Add resumable reindex progress tracking.
- Evaluate LLM reranking later as a separate change.
