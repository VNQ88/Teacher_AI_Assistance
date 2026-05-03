-- Manual versioned SQL for RAG-ready document_chunks (Option B embedding mapping)
-- Liquibase is disabled; run manually by deployment script.

CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE document_chunks
    ADD COLUMN IF NOT EXISTS classroom_id BIGINT NULL,
    ADD COLUMN IF NOT EXISTS chunk_type VARCHAR(20) NOT NULL DEFAULT 'TEXT',
    ADD COLUMN IF NOT EXISTS page_from INT NULL,
    ADD COLUMN IF NOT EXISTS page_to INT NULL,
    ADD COLUMN IF NOT EXISTS token_count INT NULL,
    ADD COLUMN IF NOT EXISTS embedding halfvec(3072),
    ADD COLUMN IF NOT EXISTS metadata_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;

UPDATE document_chunks
SET metadata_jsonb = COALESCE(
        CASE
            WHEN metadata IS NULL OR btrim(metadata) = '' THEN '{}'::jsonb
            WHEN left(btrim(metadata), 1) = '{' THEN metadata::jsonb
            ELSE jsonb_build_object('raw_metadata', metadata)
            END,
        '{}'::jsonb
                     )
WHERE metadata_jsonb = '{}'::jsonb;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_document_chunk_doc_index'
    ) THEN
        ALTER TABLE document_chunks
            ADD CONSTRAINT uk_document_chunk_doc_index UNIQUE (document_id, chunk_index);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_chunk_subject_classroom
    ON document_chunks(subject_id, classroom_id);

CREATE INDEX IF NOT EXISTS idx_chunk_doc_chunkindex
    ON document_chunks(document_id, chunk_index);

CREATE INDEX IF NOT EXISTS idx_chunk_metadata_jsonb_gin
    ON document_chunks USING gin (metadata_jsonb);

CREATE INDEX IF NOT EXISTS idx_chunk_embedding_ivfflat
    ON document_chunks USING ivfflat (embedding halfvec_cosine_ops)
    WITH (lists = 200)
    WHERE embedding IS NOT NULL;
