-- changeset VNQ88:20-migrate-to-digitalocean-qwen3-vector-1024
-- DigitalOcean migration:
-- - Gemini 3072-dimensional halfvec embeddings cannot be reused with Qwen3 1024-dimensional vectors.
-- - Remove old document-derived data so documents can be uploaded/processed again with the new provider.
-- - Preserve users, subjects, classrooms, question banks, exams, submissions, and chat history.

DELETE FROM message_source_chunks
WHERE chunk_id IN (SELECT id FROM document_chunks);

UPDATE question_banks
SET source_document_id = NULL
WHERE source_document_id IS NOT NULL;

DELETE FROM document_chunks;
DELETE FROM document_nodes;
DELETE FROM documents;

DROP INDEX IF EXISTS idx_chunk_embedding_hnsw;

ALTER TABLE document_chunks
    ALTER COLUMN embedding TYPE vector(1024)
    USING NULL;

COMMENT ON COLUMN document_chunks.embedding
    IS 'DigitalOcean Qwen3 Embedding 0.6B vectors stored as vector(1024)';

CREATE INDEX IF NOT EXISTS idx_chunk_embedding_hnsw
    ON document_chunks USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;
