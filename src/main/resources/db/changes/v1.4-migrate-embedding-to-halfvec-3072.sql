-- liquibase formatted sql
-- changeset VNQ88:6-migrate-embedding-to-halfvec-3072

-- ANN indexes for float32 vector are limited at high dimensions; move to halfvec(3072).
DROP INDEX IF EXISTS idx_document_chunks_embedding_hnsw;
DROP INDEX IF EXISTS idx_chunk_embedding_hnsw;
DROP INDEX IF EXISTS idx_chunk_embedding_ivfflat;

ALTER TABLE document_chunks
    ALTER COLUMN embedding TYPE halfvec(3072)
    USING embedding::halfvec(3072);

CREATE INDEX IF NOT EXISTS idx_chunk_embedding_hnsw
    ON document_chunks USING hnsw (embedding halfvec_cosine_ops)
    WHERE embedding IS NOT NULL;

COMMENT ON COLUMN document_chunks.embedding
IS 'Gemini embedding vectors stored as halfvec(3072)';

