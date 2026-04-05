# RAG Chatbot Skeleton (SpringAI + Gemini)

## What is included
- Subject-scoped chat session APIs:
  - `POST /chat/sessions`
  - `GET /chat/sessions`
  - `GET /chat/sessions/{sessionId}`
  - `PATCH /chat/sessions/{sessionId}/close`
- RAG message APIs:
  - `POST /chat/sessions/{sessionId}/messages`
  - `GET /chat/sessions/{sessionId}/messages`
- Option B vector mapping:
  - `DocumentChunk` does not map `embedding` field directly.
  - Repository uses native SQL + vector literal cast.
- Ingestion skeleton:
  - Markdown chunking service
  - Chunk metadata builder
  - Embedding gateway placeholder
  - Persist chunks + update embedding via native query

## Required migration
Run SQL script manually (Liquibase disabled):
- `src/main/resources/db/changes/V20260331__rag_document_chunks_schema.sql`

## Notes
- Current Gemini gateways are skeleton placeholders.
- Replace placeholder logic with Spring AI `ChatClient` and embedding model integration.
- `MarkdownChunkingService` currently has a simplified chunking implementation; extend it to exactly match your `chunking.txt` production strategy.

