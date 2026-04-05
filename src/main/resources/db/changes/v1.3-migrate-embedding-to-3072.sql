-- liquibase formatted sql
-- changeset VNQ88:5-migrate-embedding-to-3072

-- Existing 1536-d vectors cannot be cast to 3072 dimensions.
-- Clear old embeddings, drop ANN indexes, resize column, then rebuild ANN index.
UPDATE document_chunks
SET embedding = NULL
WHERE embedding IS NOT NULL;

DROP INDEX IF EXISTS idx_document_chunks_embedding_hnsw;
DROP INDEX IF EXISTS idx_chunk_embedding_hnsw;
DROP INDEX IF EXISTS idx_chunk_embedding_ivfflat;

ALTER TABLE document_chunks
    ALTER COLUMN embedding TYPE vector(3072);

-- Skip ANN index creation here because vector ANN index is limited to 2000 dimensions.
-- v1.4 converts column to halfvec(3072) and then creates ANN index safely.

COMMENT ON COLUMN document_chunks.embedding
IS 'Gemini embedding vectors (3072 dimensions)';
