-- liquibase formatted sql
-- changeset VNQ88:4-add-vector-column

-- 1. Bật extension TRƯỚC khi dùng
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. Thêm cột embedding
ALTER TABLE document_chunks
    ADD COLUMN IF NOT EXISTS embedding vector(3072);

COMMENT ON COLUMN document_chunks.embedding
IS 'Gemini embedding vectors (3072 dimensions)';
